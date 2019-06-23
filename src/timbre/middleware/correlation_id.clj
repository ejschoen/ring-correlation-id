(ns timbre.middleware.correlation-id
  (:require [taoensso.timbre :as timbre])
  (:require [ring.middleware.correlation-id :as ring-correlation-id]))

(defn middleware
  [data]
  (update-in data [:correlation-id] #(or % ring-correlation-id/*correlation-id*)))

(defn merge-correlation-id-middleware!
  []
  (timbre/merge-config! {:middleware [#'middleware]}))

(defmacro with-correlation-id-middleware
  [& body]
  `(timbre/with-merged-config
     {:middleware [#'middleware]}
     ~@body))
