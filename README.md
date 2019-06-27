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
;; Also sets Timbre *context* to {:correlation-id *correlation-id*}, so that the correlation id is natively
;; accessible to appenders
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

## License

Copyright © 2019 Eric Schoen

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
