# ring-correlation-id

A Ring-compatible Clojure library for correlation id middleware, to aid in tracing activity in a distributed system.

## Usage

```clojure
(use '[ring.middleware.correlation-id :as r-id])
;; For clj-http, provides an additional-middleware function the creates or conveys the current
;; correlation id:
(use '[clj-http.client :as http])
(use '[clj-http.middleware.correlation-id :as c-id])

;; For Ring: r-id/wrap-correlation-id ensures a :correlation-id header, and binds 
;; ring.middleware.correlation-id/*correlation-id*.
;; Also sets Timbre *context* to {:correlation-id *correlation-id*}, 
;; so that the correlation id is natively accessible to appenders.
;; c-id/ring-wrap-correlation-id adds additional middleware to clj-http, to inject
;; *correlation-id* into the request headers.

(def app
  (-> handler
      c-id/ring-wrap-correlation-id
      r-id/wrap-correlation-id))

(defn my-handler [request]
  {:status 200
   :body (format "Your correlation ID: %s" r-id/*correlation-id*)})


;; To just add correlation-id middleware to clj-http:
(http/with-additional-middleware
  [#'c-id/wrap-correlation-id]
  (http/get ...))

;; For Timbre, provides middleware that adds :correlation-id to the data map, so it's available
;; to all appenders.
;; This is most useful, for instance, with a custom appender that puts correlation-id into
;; saved state--perhaps into logstash.
(use '[timbre.middleware.correlation-id :as t-id])

(t-id/merge-correlation-id-middleware!)
;; or
(t-id/with-correlation-id-middleware
  ... your body here )

```      

To tie this all together, use a Timbre appender (such as `taoensso.timbre.appenders.3rd-party.logstash`) in all communicating applications to write to a common data store.  

## OpenTelemetry tracing

`opentelemetry.middleware` is the W3C trace-context half of the library: the same
idea as the correlation id above, but over OpenTelemetry's `traceparent` /
`tracestate` headers, so traces join up with anything else that speaks
OpenTelemetry.

```clojure
(require '[opentelemetry.middleware :as otmw])

;; Once per process.  With no exporter the spans still exist -- they propagate
;; between services and stamp log lines -- and are discarded.
(otmw/create-open-telemetry! {:sampler "on"
                              :tracer-attributes {"service.name" "my-service"}})

;; For Ring: a SERVER span per request, parented on the inbound traceparent when
;; there is one, current for the handler, with clj-http's telemetry middleware
;; installed so outbound calls made on the request thread continue the trace.
(def app (otmw/ring-wrap-telemetry-span handler))

;; Anywhere else -- a scheduled job, a queue worker, a unit of work worth its own
;; span.  with-span is a child of whatever span is current, or a root when there
;; is none, and installs the clj-http middleware for its body, so this outbound
;; call carries traceparent:
(otmw/with-span "process-work-item"
  (http/post "http://other-service/work" {:body payload}))

;; For Timbre: every log line gets traceID=/spanID= while a valid span is current.
(otmw/timbre-with-telemetry-span-middleware
  (info "this line names its trace"))
```

### Crossing a thread or process boundary

OpenTelemetry's current context is a Java thread-local, not a Clojure dynamic
binding: neither `future` nor `bound-fn` carries it, so work handed to an
executor, put on a queue, or serialized to another process arrives with no trace
and starts a root span of its own.  Carry it explicitly:

```clojure
;; Within one process: run f on another thread under this thread's context.
(.submit executor (otmw/wrap-with-current-context (fn [] (do-the-work item))))

;; Across a queue or a process boundary: capture plain header strings that can be
;; stored or serialized, and make them current again where the work is picked up.
(let [item (assoc item :trace-context (otmw/current-trace-context))]  ; nil if no span
  (put-on-queue item))

;; ... later, possibly in another process, on another thread:
(otmw/with-trace-context (:trace-context item)
  (otmw/with-span "process-work-item"          ; a child of the span that planned it
    (do-the-work item)))
```

`current-trace-context` returns nil when no valid span is current, and
`with-trace-context` of nil runs its body unchanged, so code written this way
behaves identically when telemetry is not configured.

The captured map holds nothing but strings, so it survives JSON or EDN intact.
`with-trace-context` reads header names as strings or as keywords, so an item
that came back through `(cheshire/parse-string body true)` — the usual shape on
the far side of an HTTP hop — still carries its trace.

## License

Copyright © 2019 Eric Schoen

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
