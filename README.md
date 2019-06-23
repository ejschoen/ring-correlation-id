# ring-correlation-id

A Ring-compatible Clojure library for correlation id middleware, to aid in tracing activity in a distributed system.

## Usage

```clojure
(use 'ring.middleware.correlation-id)

(def app
  (-> handler
      wrap-correlation-id))

(defn my-handler [request]
  {:status 200
   :body (format "Your correlation ID: %s" *correlation-id*)})

```      

## License

Copyright © 2019 Eric Schoen

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
