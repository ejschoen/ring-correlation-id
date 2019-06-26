(ns timbre.middleware.correlation-id
  (:require [taoensso.timbre :as timbre])
  (:require [ring.middleware.correlation-id :as ring-correlation-id]))

(defn middleware
  [data]
  (update-in data [:correlation-id] #(or % ring-correlation-id/*correlation-id*)))

(defn output-fn
  ([data] (output-fn nil data))
  ([opts data]
   (if-let [correlation-id (:correlation-id data)]
     (timbre/default-output-fn opts
       (assoc data :timestamp_ (str (force (:timestamp_ data)) " " correlation-id)))
     (timbre/default-output-fn opts data))))

(def delta-config
   {:output-fn output-fn
    :middleware [#'middleware]})

(defn merge-correlation-id-middleware!
  []
  (timbre/merge-config! delta-config))

(defmacro with-correlation-id-middleware
  [& body]
  `(timbre/with-merged-config
     delta-config
     ~@body))
