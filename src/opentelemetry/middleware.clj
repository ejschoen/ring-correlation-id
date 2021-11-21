(ns opentelemetry.middleware
  (:require [clojure.core.cache.wrapped :as cache-wrapped])
  (:require [ring.middleware.correlation-id :as rcid]
            [clj-http.middleware.correlation-id :as hcid]
            [timbre.middleware.correlation-id :as tcid])
  (:require [taoensso.timbre :refer [errorf warnf debug debugf with-merged-config]])
  (:require [opentelemetry.w3c-trace-context])
  (:require [clj-http.client :as http])
  (:require [telemetry.tracing :as tracing])
  (:import [io.opentelemetry.sdk OpenTelemetrySdk OpenTelemetrySdkBuilder]
           [io.opentelemetry.sdk.resources Resource]
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

(def ^:private _ot (atom nil))
(def ^:private _tracer (atom nil))

(defn set-open-telemetry!
  "Set the open telemetry instance for this process, unless already set."
  [ot]
  (swap! _ot
         (fn [old]
           (if (not old)
             ot
             old)))
  @_ot)

(defn- get-default-propagators
  []
  (ContextPropagators/create
   (TextMapPropagator/composite
    [(W3CTraceContextPropagator/getInstance)
     (W3CBaggagePropagator/getInstance)])))

(def ^:private attribute-cache (cache-wrapped/lru-cache-factory {} :threshold 32))

(def ^:private attribute-creators
  {String #(AttributeKey/stringKey %1)
   Long #(AttributeKey/longKey %1)
   Double #(AttributeKey/doubleKey %1)
   Boolean #(AttributeKey/booleanKey %1)})


(defn ^AttributeKey get-attribute-key [name value]
  (if-let [creator  (get attribute-creators (type value))]
    (cache-wrapped/lookup-or-miss attribute-cache creator)
    nil))
                                
(defn- ^Attributes build-attributes
  [m]
  (let [^AttributesBuilder builder (Attributes/builder)]
    (doseq [[key val] m
            :let [^AttributeKey attrkey (get-attribute-key key val)]]
      (if attrkey
        (.put builder attrkey val)
        (.put builder key val)))
    (.build builder)))

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
     :or {propagators (get-default-propagators)}}]
   (when (and span-processor tracer-provider)
     (throw (Exception. "create-open-telemetry!: Optionally provide span-processor or tracer-provider, but not both.")))
   (swap! _ot
          (fn [old]
            (if (not old)
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
                                  (.setResource (.merge (.getDefault Resource)
                                                        (build-attributes tracer-attributes))))
                                (cond-> sampler (.setSampler
                                                 (cond 
                                                   (= sampler "on") (Sampler/alwaysOn)
                                                   (= sampler "off") (Sampler/alwaysOff)
                                                   (and (float? sampler) (<= 0.0 sampler 1.0)) (Sampler/traceIdRatioBased sampler)
                                                   (instance? Sampler sampler) sampler
                                                   :else nil)))
                                (cond-> span-processor (.addSpanProcessor span-processor))))))))]
                ot)
              old)))
   @_ot)
  ([]
   (create-open-telemetry! {})))

(defn ^OpenTelemetry get-open-telemetry []
  "Get the open telemetry instance for this process.  If one is not registered,
   return the noop instance."
  (or @_ot (OpenTelemetry/noop)))

(defn get-tracer [& [name]]
  "Get the tracer for this process.  If a tracer is not set, create one,
   optionally with the given name."
  (when (not @_tracer)
    ;;(debugf "Building a new tracer with name %s" name)
    (reset! _tracer (.build (.tracerBuilder (get-open-telemetry)
                                            (or name
                                                "org.ejschoen.opentelemetry.middleware")))))
  @_tracer)

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

(defmacro with-span
  "Execute body within the context of an open telemetry span,"
  [name & body]
  `(let [span# (tracing/create-span (get-tracer) ~name)]
     (try
       (with-open [^Scope scope# (.makeCurrent span#)]
         ~@body
         )
       (finally (tracing/end-span span#)))))

(defn get-context-header [req]
  "Return the W3C trace context headers as a 2-tuple list of traceparent and tracestate."
  (let [traceparent (get-in req [:headers "traceparent"])
        tracestate (get-in req [:headers "tracestate"])]
    (if traceparent
      (list traceparent tracestate)
      nil)))

(defn inject-trace-headers
  "Inject W3C trace headers into a request map, 
   based on the current open telemetry span context."
  [req]
  (if-let [^OpenTelemetry ot (get-open-telemetry)]
    (if (.isValid (.getSpanContext (Span/current)))
      (let [^TextMapPropagator propagator (.getTextMapPropagator
                                           (.getPropagators ot))
            atom-map (atom {})]
        ;;(debug propagator)
        ;;(debug (Context/current))
        (.inject propagator (Context/current) atom-map
                 (reify TextMapSetter
                   (set [_ m key value]
                     (swap! m assoc key value))))
        ;;(debug @atom-map)
        (update-in req [:headers] (fn [h] (merge h @atom-map))))
      req)
    req))
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
             ^Context new-context (.extract propagator (Context/current) req
                                            (reify TextMapGetter
                                              (get [_ obj key]
                                                (get (:headers obj) key))))
             ^Span span (.startSpan
                         (doto (.spanBuilder (get-tracer)
                                             (or (not-empty span-name)
                                                 (:uri req)))
                           (.setParent new-context)
                           (.setSpanKind SpanKind/SERVER)))]
         (try (with-open [^Scope scope (.makeCurrent span)]
                (clj-http-with-telemetry-span-middleware
                 (handler req)))
              (finally (.end span))))
       (handler req)))))

(defn timbre-wrap-telemetry-span
  [data]
  (update-in data [:correlation-id]
             #(let [context (.getSpanContext (Span/current))]
                (or %  (str (.getTraceId context) "-" (.getSpanId context))))))

(def delta-config
   {:output-fn tcid/output-fn
    :middleware [#'timbre-wrap-telemetry-span]})

(defmacro timbre-with-telemetry-span-middleware
  [& body]
  (if (map? (first body))
    `(with-merged-config (merge delta-config ~(first body))
       ~@(rest body))
    `(with-merged-config
       delta-config
       ~@body)))
