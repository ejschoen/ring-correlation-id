(ns clj-http.middleware.correlation-id
  (:require [taoensso.timbre :as timbre])
  (:require [clj-http.client :as http])
  (:require [ring.middleware.correlation-id :as ring-correlation-id])
  (:import [java.util UUID])
  )


(defn wrap-correlation-id
  [client]
  (fn
    ([req]
     (timbre/debugf "CLJ-HTTP MIDDLEWARE: Called")
     (let [correlation-id (or ring-correlation-id/*correlation-id*
                              (let [new-id (str (UUID/randomUUID))]
                                (timbre/debugf "CLJ-HTTP MIDDLEWARE: New Correlation ID %s" new-id)
                                new-id))]
       (client (assoc-in req [:headers ring-correlation-id/id-header]
                         correlation-id))))
    ([req respond raise]
     (timbre/debugf "CLJ-HTTP MIDDLEWARE: Called")
     (let [correlation-id (or ring-correlation-id/*correlation-id*
                              (let [new-id (str (UUID/randomUUID))]
                                (timbre/debugf "CLJ-HTTP MIDDLEWARE: New Correlation ID %s" new-id)
                                new-id))]
       (client req
               #(respond (assoc-in req [:headers ring-correlation-id/id-header]
                                   correlation-id)
                         raise)
               raise)))))

(defmacro with-correlation-id
  [& body]
  `(http/with-additional-middleware [#'wrap-correlation-id]
     ~@body))

(defn ring-wrap-correlation-id
  [handler]
  (fn [req]
    (with-correlation-id
      (handler req))))
          
