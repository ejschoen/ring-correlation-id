(ns opentelemetry.middleware
  (:require [clojure.core.cache.wrapped :as cache-wrapped]
            [clojure.string :as str])
  (:require [ring.middleware.correlation-id :as rcid]
            [clj-http.middleware.correlation-id :as hcid]
            [timbre.middleware.correlation-id :as tcid])
  (:require [taoensso.encore :as enc]
            [taoensso.timbre :refer [errorf warnf debug debugf with-merged-config
                                     stacktrace]])
  (:require [opentelemetry.w3c-trace-context])
  (:require [clj-http.client :as http])
  (:require [telemetry.tracing :as tracing])
  (:import [io.opentelemetry.sdk OpenTelemetrySdk OpenTelemetrySdkBuilder]
           [io.opentelemetry.sdk.resources Resource ResourceBuilder]
           [io.opentelemetry.sdk.trace SpanProcessor SdkTracerProvider]
           [io.opentelemetry.sdk.trace.samplers Sampler])
  (:import [io.opentelemetry.api OpenTelemetry GlobalOpenTelemetry]
           [io.opentelemetry.api.baggage.propagation W3CBaggagePropagator]
           [io.opentelemetry.api.common Attributes AttributesBuilder AttributeKey]
           [io.opentelemetry.api.trace
            SpanBuilder SpanContext SpanKind Span
            Tracer TraceState TraceStateBuilder]
           [io.opentelemetry.api.trace.propagation W3CTraceContextPropagator])
  (:import [io.opentelemetry.context Context Scope]
           [io.opentelemetry.context.propagation ContextPropagators
            TextMapPropagator TextMapGetter TextMapSetter ])
  
  )

;; defonce, not def: GlobalOpenTelemetry is a JVM-wide singleton that outlives any
;; namespace reload.  A plain def would hand a reloaded namespace an empty cache
;; while the global stayed claimed, and every later create-open-telemetry! would
;; try to register again and throw.
(defonce ^:private _ot (atom nil))
(defonce ^:private _tracer (atom nil))

(defn set-open-telemetry!
  "Set the open telemetry instance for this process, unless already set."
  [ot]
  (swap! _ot
         (fn [old]
           (if (not old)
             ot
             old)))
  @_ot)

;;Wow.  Calling static interface methods in Java 11 fails in Clojure 1.8
;;https://stackoverflow.com/questions/49574394/how-to-instantiate-a-stream-builder-class-in-clojure-using-java-9
(defmacro interface-static-call
  [sym & argtypes]
  `(let [m# (.getMethod ~(symbol (namespace sym))
                        ~(name sym)
                        (into-array Class ~(into [] argtypes)))]
     (fn [& args#]
       (.invoke m# nil (to-array args#)))))

(defn- get-default-propagators
  []
  ((interface-static-call ContextPropagators/create io.opentelemetry.context.propagation.TextMapPropagator)
   (TextMapPropagator/composite
    [(W3CTraceContextPropagator/getInstance)
     (W3CBaggagePropagator/getInstance)])))

(def ^:private attribute-cache (cache-wrapped/lru-cache-factory {} :threshold 32))

(def ^:private attribute-creators
  {String (fn [name] ((interface-static-call AttributeKey/stringKey String) name))
   Long (fn [name]  ((interface-static-call AttributeKey/longKey String) name))
   Double (fn [name]  ((interface-static-call AttributeKey/doubleKey String) name))
   Boolean (fn [name] ((interface-static-call AttributeKey/booleanKey String) name))})


(defn ^AttributeKey get-attribute-key [name value]
  (if-let [creator  (get attribute-creators (type value))]
    (cache-wrapped/lookup-or-miss attribute-cache name creator)
    nil))
                                
(defn- ^Attributes build-attributes
  "Build an Attributes object from a map of key/value pairs."
  [m]
  (let [^AttributesBuilder builder (Attributes/builder)]
    (doseq [[key val] m
            :let [^AttributeKey attrkey (get-attribute-key (name key) val)]]
      (if attrkey
        (.put builder attrkey val)
        (.put builder (name key) val)))
    (.build builder)))

(defn- ^Resource build-resource
  "Build a Resource object from a map of key/value pairs."
  [m]
  (let [^ResourceBuilder builder (Resource/builder)]
    (doseq [[key val] m
            :let [^AttributeKey attrkey (get-attribute-key (name key) val)]]
      (if attrkey
        (.put builder attrkey val)
        (.put builder (name key) val)))
    (.build builder)))

(defn- ^OpenTelemetry register-global!
  "Build an OpenTelemetrySdk from opts and install it as this process's
   GlobalOpenTelemetry, returning whatever ends up registered.

   GlobalOpenTelemetry takes exactly one registration for the life of the JVM,
   and other libraries on the classpath claim it too: Solr 10's
   OpenTelemetryConfigurator calls GlobalOpenTelemetry.set from
   CoreContainer.load, which Solr 9 never did.  Losing that race is not worth
   propagating -- adopt what is registered instead, so spans still reach a
   provider and the caller is not left retrying a registration that can never
   succeed.

   The sdk built here is abandoned when the race is lost.  Nothing ever feeds it
   a span, so the cost is one idle span-processor thread, once per process."
  [{:keys [propagators span-processor tracer-provider sampler tracer-attributes]}]
  (try
    (let [ot (.buildAndRegisterGlobal
              (doto (OpenTelemetrySdk/builder)
                (cond-> propagators
                  (.setPropagators propagators))
                (cond-> tracer-provider
                  (.setTracerProvider tracer-provider))
                (cond-> (or span-processor tracer-attributes)
                  (.setTracerProvider
                   (.build
                    (doto (SdkTracerProvider/builder)
                      (cond-> tracer-attributes
                        (.setResource (.merge (Resource/getDefault)
                                              (build-resource tracer-attributes))))
                      (cond-> sampler (.setSampler
                                       (cond 
                                         (= sampler "on") ((interface-static-call Sampler/alwaysOn))
                                         (= sampler "off") ((interface-static-call Sampler/alwaysOff))
                                         (and (float? sampler) (<= 0.0 sampler 1.0)) ((interface-static-call Sampler/traceIdRatioBased Double) sampler)
                                         (instance? Sampler sampler) sampler
                                         :else nil)))
                      (cond-> span-processor (.addSpanProcessor span-processor))))))))]
      (taoensso.timbre/info "Initializing open telemetry instance")
      ot)
    (catch IllegalStateException e
      (warnf (str "GlobalOpenTelemetry was already registered by something else, so "
                  "this configuration is ignored and the registered instance is used "
                  "instead: %s")
             (.getMessage e))
      (GlobalOpenTelemetry/get))))

(defn create-open-telemetry!
  "If the open telemetry instance for this process is not already set,
   create one and registery it as global.
   Supported entries in optional map parameter:
     propagators: opentelemetry.api.trace.propagation trace context propagator
                  (defaults to value from get-default-propagators)
     span-processor: opentelemetry.sdk.trace.SpanProcessor instance
     tracer-provider: opentelemetry.sdk.trace.SdkTracerProvider instance
     tracer-attributes: Map of attributes to attached to a tracer when span-processor is provided."
  ([{:keys [propagators span-processor tracer-provider sampler
            tracer-attributes]
     :or {propagators (get-default-propagators)}
     :as opts}]
   (when (and span-processor tracer-provider)
     (throw (Exception. "create-open-telemetry!: Optionally provide span-processor or tracer-provider, but not both.")))
   ;; Double-checked locking rather than swap!.  Registering the global is a side
   ;; effect, and swap! re-runs its function whenever the compare-and-set loses,
   ;; so two threads arriving together would each register and the loser would
   ;; throw.
   (when-not @_ot
     (locking _ot
       (when-not @_ot
         (reset! _ot (register-global! (assoc opts :propagators propagators))))))
   @_ot)
  ([]
   (create-open-telemetry! {})))

(defn ^OpenTelemetry get-open-telemetry []
  "Get the open telemetry instance for this process.  If one is not registered,
   return the noop instance."
  (or @_ot (OpenTelemetry/noop)))

(defn get-tracer
  "Get the tracer for this process.  If a tracer is not set, create one,
   optionally with the given name.

   INVARIANT: _tracer only ever holds a tracer built from _ot.  A tracer is
   cached only when an OpenTelemetry instance is actually registered; until
   then get-open-telemetry answers the noop instance, and a tracer built from
   THAT is returned uncached, on every call.

   That invariant is the point, because this function memoizes.  Without it,
   ONE call made before create-open-telemetry! - anything that spans, traces or
   logs during boot - cached a NOOP tracer for the life of the process, and
   every later with-span started an invalid span forever: silently, with no
   exception, no traceparent and no traceID= on any log line, indistinguishable
   from telemetry being switched off.  Caching only what came from a registered
   instance makes that unrepresentable rather than merely unlikely, and it
   holds for a get-tracer racing an in-progress registration too - _ot is read
   ONCE, and the tracer is built from exactly the value that decided whether to
   cache it.

   set-tracer! is the one way past this: it installs a tracer of the caller's
   own when none is cached yet (it is set-if-absent, not an override), so a
   caller that hands it a tracer built from an unregistered instance owns the
   consequence."
  [& [name]]
  (or @_tracer
      ;;(debugf "Building a new tracer with name %s" name)
      (let [ot @_ot
            tracer (.build (.tracerBuilder (or ot (OpenTelemetry/noop))
                                           (or name
                                               "org.ejschoen.opentelemetry.middleware")))]
        (when ot
          (reset! _tracer tracer))
        tracer)))

(defn set-tracer! [tracer]
  "Set the tracer for this process, if not already set."
  (when (not @_tracer)
    (reset! _tracer tracer))
  @_tracer)

(defn reset-open-telemetry! []
  "Reset open telemetry in this process. This is for testing purposes only."
  (GlobalOpenTelemetry/resetForTest)
  (reset! _ot nil)
  (reset! _tracer nil))

(defn get-context-header [req]
  "Return the W3C trace context headers as a 2-tuple list of traceparent and tracestate."
  (let [traceparent (get-in req [:headers "traceparent"] (get-in req [:headers "Traceparent"]))
        tracestate (get-in req [:headers "tracestate"] (get-in req [:headers "Tracestate"]))]
    (if traceparent
      (list traceparent tracestate)
      nil)))

(defn inject-trace-headers
  "Inject W3C trace headers into a request map, 
   based on the current open telemetry span context."
  [req]
  (if-let [^OpenTelemetry ot (get-open-telemetry)]
    (if (.isValid (.getSpanContext ((interface-static-call Span/current))))
      (let [^TextMapPropagator propagator (.getTextMapPropagator
                                           (.getPropagators ot))
            atom-map (atom {})]
        ;;(debug propagator)
        ;;(debug (Context/current))
        (.inject propagator ((interface-static-call Context/current)) atom-map
                 (reify TextMapSetter
                   (set [_ m key value]
                     (swap! m assoc key value))))
        ;;(debug @atom-map)
        (update-in req [:headers] (fn [h] (merge h @atom-map))))
      req)
    req))

;;; Carrying a trace across a thread or a process boundary.
;;;
;;; OpenTelemetry's current Context is a Java thread-local, not a Clojure
;;; dynamic binding: neither future nor bound-fn carries it, and a work item
;;; handed to an executor -- or serialized and picked up by another process --
;;; arrives on a thread that has no trace at all, where every span it starts is
;;; a fresh root.  What follows is the two halves of carrying it anyway: capture
;;; the current context as plain W3C header strings that can travel on anything,
;;; and make those strings current again on the far side.  wrap-with-current-context
;;; is the shortcut for the case where the boundary is only a thread, not a process.

(defn current-trace-context
  "Capture this thread's current W3C trace context as a map of header name to
   header value, e.g. {\"traceparent\" \"00-<trace>-<span>-01\"}, which is safe
   to put on a queue item, in EDN/JSON on the wire, or in any other carrier that
   holds strings.  A JSON round trip that keywordizes the names is fine:
   extract-trace-context reads either.

   Returns nil when no valid span is current -- there is nothing to propagate
   then, and nil lets a caller tell 'no trace' from 'a trace' rather than
   propagating an all-zero id.  Also returns nil when the registered propagator
   injects nothing (the noop OpenTelemetry, i.e. telemetry not configured)."
  []
  (let [^OpenTelemetry ot (get-open-telemetry)]
    (when (.isValid (.getSpanContext ((interface-static-call Span/current))))
      (let [^TextMapPropagator propagator (.getTextMapPropagator (.getPropagators ot))
            carrier (atom {})]
        (.inject propagator ((interface-static-call Context/current)) carrier
                 (reify TextMapSetter
                   (set [_ m key value]
                     (swap! m assoc key value))))
        (not-empty @carrier)))))

(defn ^Context extract-trace-context
  "Extract the W3C trace headers in `headers` -- the map current-trace-context
   returns, or any map of header name to header value -- into an
   io.opentelemetry.context.Context.

   Header names may be strings or keywords.  current-trace-context produces
   strings, but a map that has been through JSON on the way here usually comes
   back keywordized (cheshire/parse-string with keywords? true, and every
   Clojure JSON reader that defaults that way), and losing the trace to that
   would be silent.

   The extraction is layered on the context that is current on this thread, so
   what comes back is that context plus whatever the headers add.  Returns nil
   when the result holds no valid span -- headers is empty, or carries no usable
   trace and nothing was current -- so a caller can skip making a context
   current instead of making an invalid one current.  When a valid span IS
   already current and the headers add nothing, the current context comes back,
   and making it current again is a no-op.

   Only a MAP is read.  Anything else - a string, a vector, whatever survived a
   wire shape nobody expected - answers nil rather than being handed to the
   propagator: the TextMapGetter below calls clojure.core/keys on the carrier,
   which throws on a non-map the moment a propagator iterates it, and losing a
   trace must never cost the work that was carrying it."
  [headers]
  (when (and (map? headers) (seq headers))
    (let [^OpenTelemetry ot (get-open-telemetry)
          ^TextMapPropagator propagator (.getTextMapPropagator (.getPropagators ot))
          ^Context context (.extract propagator ((interface-static-call Context/current))
                                     headers
                                     (reify TextMapGetter
                                       (get [_ carrier key]
                                         (or (clojure.core/get carrier key)
                                             (clojure.core/get carrier (keyword key))))
                                       (keys [_ carrier]
                                         (map name (clojure.core/keys carrier)))))
          ^Span span ((interface-static-call Span/fromContext io.opentelemetry.context.Context)
                      context)]
      (when (.isValid (.getSpanContext span))
        context))))

(defmacro with-trace-context
  "Execute body with the trace context in `headers` current on this thread.

   This is the receiving half of current-trace-context: use it on the thread
   that picks work up -- a queue worker, an executor task, an item restored from
   another process -- so the spans it starts join the trace that planned the
   work instead of rooting a new one.  The Scope is always closed, so the
   thread's previous context is current again after the body.

   Header names may be strings or keywords, so headers that have been through
   JSON and come back keywordized still work.

   With nil or empty headers, or headers carrying no valid span context, the
   body runs unchanged under whatever context the thread already had."
  [headers & body]
  `(let [f# (fn [] ~@body)]
     (if-let [^Context context# (extract-trace-context ~headers)]
       (with-open [^Scope scope# (.makeCurrent context#)]
         (f#))
       (f#))))

(defmacro without-trace-context
  "Execute body with NO trace context current on this thread: the root context
   is made current, so Span/current is invalid, current-trace-context returns
   nil, and any with-span inside starts a new ROOT trace.  The Scope is always
   closed, so whatever was current before the body is current again after it.

   Use it where work that is NOT part of the caller's trace runs on the
   caller's thread.  The case it exists for: an administrative HTTP request
   that re-creates a batch of previously-planned work - a queue restore, a
   reconcile from durable rows - inline on the request thread.  Without this,
   every one of those items would capture the request's span and thousands of
   unrelated documents would join one trace whose root is an operator clicking
   a button.  Rooting them is not a detail: a trace that means \"everything that
   was in the database when someone restarted a queue\" means nothing.

   This is about the AMBIENT span, not about telemetry being on: with no SDK
   registered the body behaves exactly as it would anyway."
  [& body]
  `(with-open [^Scope scope# (.makeCurrent ((interface-static-call Context/root)))]
     ~@body))

(defn wrap-with-current-context
  "Return a fn that runs f under the io.opentelemetry.context.Context that is
   current on THIS thread at the moment wrap-with-current-context is called.

   This is what io.opentelemetry.context.Context's own wrap does for a Runnable
   or a Callable, done here so the result is still a Clojure fn: it takes the
   arguments f takes, returns what f returns, and can be handed straight to an
   executor.  Use it when the context is being carried within one process (a
   future, an ExecutorService task); use current-trace-context /
   with-trace-context when it has to survive serialization.

   The Scope is closed after each call, so the borrowed context does not leak
   onto the pool thread that ran it."
  [f]
  (let [^Context context ((interface-static-call Context/current))]
    (fn [& args]
      (with-open [^Scope scope (.makeCurrent context)]
        (apply f args)))))

(defn clj-http-wrap-telemetry-span
  [client]
  (fn
    ([req]
     ;;(debugf "CLJ-HTTP TELEMETRY MIDDLEWARE: Called")
     (client (inject-trace-headers req)))
    ([req respond raise]
     ;;(debugf "CLJ-HTTP TELEMETRY MIDDLEWARE: Called")
     (client (inject-trace-headers req)
             respond raise))))

(defmacro clj-http-with-telemetry-span-middleware
  [& body]
  `(http/with-additional-middleware [#'clj-http-wrap-telemetry-span]
     ~@body))

(def ^:private exception-escaped (atom nil))

(defn ^AttributeKey get-exception-escaped
  []
  (when-not @exception-escaped
    (reset! exception-escaped ((get attribute-creators Boolean) "exception.escaped")))
  @exception-escaped)

(def escaped-fun )

(defn record-exception
  ([^Throwable e escaped?]
   (if-let [current-span ((interface-static-call Span/current))]
     (when (.isValid (.getSpanContext current-span))
       (record-exception current-span e escaped?))))
  ([^Span span ^Throwable e escaped?]
   (let [attr-fn (interface-static-call Attributes/of AttributeKey Object)]
     (.recordException span e (attr-fn (get-exception-escaped) escaped?)))))

(defmacro with-span
  "Execute body inside an OpenTelemetry span named id.

   The span joins the trace that is already running on this thread: it is a
   child of the current span when that span's context is valid, and a root span
   when it is not.  It is made current for the dynamic extent of body through a
   Scope that is always closed, so whatever was current before -- the parent
   span, or no valid span at all -- is current again on the way out.  Leaving
   that Scope open would leave the ENDED span current on the thread, and every
   later log line on it would carry a dead span id.

   clj-http's telemetry middleware is installed for body, so any clj-http call
   made on this thread inside the span injects traceparent and the service it
   calls continues the same trace.

   An exception thrown by body is recorded on the span, marked as escaped, and
   rethrown.  The span is always ended.

   Note that OpenTelemetry's current context is a thread-local: body running on
   a thread this macro did not enter (a future, an executor task) does not see
   the span.  Carry it with wrap-with-current-context, or with
   current-trace-context / with-trace-context across a process boundary."
  [id & body]
  `(let [^Span parent# ((interface-static-call Span/current))
         ^Span span# (if (.isValid (.getSpanContext parent#))
                       (tracing/create-span (get-tracer) ~id parent#)
                       (tracing/create-span (get-tracer) ~id))]
     (try
       (with-open [^Scope scope# (.makeCurrent span#)]
         (clj-http-with-telemetry-span-middleware
          ~@body))
       (catch Throwable t#
         (when span# (record-exception span# t# true))
         (throw t#))
       (finally (tracing/end-span span#)))))

(defn ring-wrap-telemetry-span
  "Ring handler that creates a span for the dynamic extent of the wrapped
   handler, with a parent context when the incoming request has the appropriate
   W3C trace context headers."
  ([handler]
   (ring-wrap-telemetry-span handler {}))
  ([handler {:keys [span-name]}]
   (fn [req]
     (debugf "In ring-wrap-telemetry-span")
     (if-let [^OpenTelemetry ot @_ot]
       (let [^TextMapPropagator propagator (.getTextMapPropagator
                                            (or (.getPropagators ot)
                                                (ContextPropagators/noop)))
             ^Context new-context (.extract propagator ((interface-static-call Context/current)) req
                                            (reify TextMapGetter
                                              (get [_ obj key]
                                                (let [val (get (:headers obj) key)]
                                                  #_(println (format "**** ring-wrap-telemetry-span: get context with key %s: %s"
                                                                     key val))
                                                  val))))
             ^Span span (.startSpan
                         (doto (.spanBuilder (get-tracer)
                                             (or (not-empty span-name)
                                                 (:uri req)))
                           (.setParent new-context)
                           (.setSpanKind SpanKind/SERVER)))]
         (try (with-open [^Scope scope (.makeCurrent span)]
                (clj-http-with-telemetry-span-middleware
                 #_(println (format "**** ring-wrap-telemetry-span: Invoking next handler with TraceID %s" (.getTraceId (.getSpanContext span))))
                 (handler req)))
              (catch Throwable e
                #_(println (format "**** ring-wrap-telemetry-span: Exception: %s" (.getMessage e)))
                (record-exception span e true)
                (throw e))
              (finally (.end span))))
       (handler req)))))

(defn timbre-wrap-telemetry-span
  [data]
  (let [context (.getSpanContext ((interface-static-call Span/current)))]
    (update-in data [:context]
               (fn [old]
                 (assoc old
                        :trace-id (.getTraceId context)
                        :span-id (.getSpanId context)
                        :trace-flags (.asHex (.getTraceFlags context)))))))

(defn timbre-output-fn
  "Default (fn [data]) -> string output fn.
  Use`(partial default-output-fn <opts-map>)` to modify default opts."
  ([     data] (timbre-output-fn nil data))
  ([opts data] ; For partials
   (let [{:keys [no-stacktrace? stacktrace-fonts]} opts
         {:keys [level ?err #_vargs msg_ ?ns-str ?file hostname_
                 timestamp_ ?line context output-opts]} data
         {:keys [trace-id span-id trace-flags]} context
         sb (StringBuilder.)]
     (.append sb (force timestamp_))
     (when (and trace-id (not (re-matches #"0*" trace-id)))
       (.append sb " traceID=")
       (.append sb trace-id)
       (.append sb " traceFlags=")
       (.append sb trace-flags))
     (when (and span-id (not (re-matches #"0*" span-id)))
       (.append sb " spanID=")
       (.append sb span-id))
     (.append sb \space)
     (.append sb (force hostname_))
     (.append sb \space)
     (.append sb (str/upper-case (name level)))
     (.append sb \space)
     (.append sb \[)
     (.append sb (or ?ns-str ?file "?"))
     (.append sb \:)
     (.append sb (or ?line "?"))
     (.append sb "] - ")
     (.append sb (force msg_))
     (when-not no-stacktrace?
       (when-let [err ?err]
         (.append sb enc/system-newline)
         (.append sb (if-let [ef (:error-fn output-opts)]
                       (ef data)
                       (stacktrace err opts)))))
     (.toString sb))))

(def delta-config
   {:output-fn timbre-output-fn
    :middleware [#'timbre-wrap-telemetry-span]})

(defmacro timbre-with-telemetry-span-middleware
  [& body]
  (if (map? (first body))
    `(with-merged-config (merge delta-config ~(first body))
       ~@(rest body))
    `(with-merged-config
       delta-config
       ~@body)))
