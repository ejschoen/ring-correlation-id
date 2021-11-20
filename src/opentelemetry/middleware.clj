(ns opentelemetry.middleware
  (:require [ring.middleware.correlation-id :as rcid]
            [clj-http.middleware.correlation-id :as hcid]
            [timbre.middleware.correlation-id :as tcid])
  (:require [taoensso.timbre :refer [errorf warnf debug debugf with-merged-config]])
  (:require [opentelemetry.w3c-trace-context])
  (:require [clj-http.client :as http])
  (:require [telemetry.tracing :as tracing])
  (:import [io.opentelemetry.sdk OpenTelemetrySdk OpenTelemetrySdkBuilder])
  (:import [io.opentelemetry.api OpenTelemetry GlobalOpenTelemetry]
           [io.opentelemetry.api.baggage.propagation W3CBaggagePropagator]
           [io.opentelemetry.api.trace
            SpanBuilder SpanContext SpanKind Span
            Tracer TraceState TraceStateBuilder]
           [io.opentelemetry.api.trace.propagation W3CTraceContextPropagator])
  (:import [io.opentelemetry.context Context]
           [io.opentelemetry.context.propagation ContextPropagators
            TextMapPropagator TextMapGetter TextMapSetter ])
  )

(def ^:private _ot (atom nil))
(def ^:private _tracer (atom nil))

(defn set-open-telemetry! [ot]
  (swap! _ot
         (fn [old]
           (if (not old)
             ot
             old)))
  @_ot)

(defn create-open-telemetry! []
  (swap! _ot
         (fn [old]
           (if (not old)
             (let [propagators (ContextPropagators/create
                                (TextMapPropagator/composite
                                 [(W3CTraceContextPropagator/getInstance)
                                  (W3CBaggagePropagator/getInstance)]))
                   ot (.buildAndRegisterGlobal (doto (OpenTelemetrySdk/builder)
                                                 (.setPropagators propagators)))]
               ot)
             old)))
  @_ot)

(defn ^OpenTelemetry get-open-telemetry []
  (or @_ot (OpenTelemetry/noop)))

(defn get-tracer [& [name]]
  (when (not @_tracer)
    (debugf "Building a new tracer with name %s" name)
    (reset! _tracer (.build (.tracerBuilder (get-open-telemetry)
                                            (or name
                                                "org.ejschoen.opentelemetry.middleware")))))
  @_tracer)

(defn set-tracer! [tracer]
  (when (not @_tracer)
    (reset! _tracer tracer))
  @_tracer)

(defn reset-open-telemetry! []
  (GlobalOpenTelemetry/resetForTest)
  (reset! _ot nil)
  (reset! _tracer nil))

(defmacro with-span
  [name & body]
  `(let [span# (tracing/create-span (get-tracer) ~name)]
     (try
       (with-open [scope# (.makeCurrent span#)]
         ~@body
         )
       (finally (tracing/end-span span#)))))

(defn get-context-header [req]
  (let [traceparent (get-in req [:headers "traceparent"])
        tracestate (get-in req [:headers "tracestate"])]
    (if traceparent
      (list traceparent tracestate)
      nil)))

(defn ring-wrap-telemetry-span
  ([handler]
   (ring-wrap-telemetry-span handler {}))
  ([handler {:keys [span-name]}]
   (fn [req]
     (debugf "In ring-wrap-telemetry-span")
     (if-let [^OpenTelemetry ot (get-open-telemetry)]
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
         (try (.makeCurrent span)
              (handler req)
              (finally (.end span))))
       (handler req)))))

(defn inject-trace-headers
  [req]
  (if-let [^OpenTelemetry ot (get-open-telemetry)]
    (let [^TextMapPropagator propagator (.getTextMapPropagator
                                         (.getPropagators ot))
          atom-map (atom {})]
      (debug propagator)
      (debug (Context/current))
      (.inject propagator (Context/current) atom-map
               (reify TextMapSetter
                 (set [_ m key value]
                   (swap! m assoc key value))))
      (debug @atom-map)
      (update-in req [:headers] (fn [h] (merge h @atom-map))))
    req))

(defn clj-http-wrap-telemetry-span
  [client]
  (fn
    ([req]
     (debugf "CLJ-HTTP TELEMETRY MIDDLEWARE: Called")
     (client (inject-trace-headers req)))
    ([req respond raise]
     (debugf "CLJ-HTTP TELEMETRY MIDDLEWARE: Called")
     (client (inject-trace-headers req)
             respond raise))))

(defmacro clj-http-with-telemetry-span-middleware
  [& body]
  `(http/with-additional-middleware [#'clj-http-wrap-telemetry-span]
     ~@body))

(defn timbre-wrap-telemetry-span
  [data]
  (update-in data [:correlation-id]
             #(or % (.getSpanId (.getSpanContext (Span/current))))))

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
