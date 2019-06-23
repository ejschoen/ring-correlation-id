(ns clj-http.middleware.correlation-id
  (:require [ring.middleware.correlation-id :as ring-correlation-id])
  (:import [java.util UUID])
  )


(defn wrap-correlation-id
  [client]
  (fn [req]
    (let [correlation-id (or ring-correlation-id/*correlation-id* (str (UUID/randomUUID)))]
      (client (assoc-in req [:headers ring-correlation-id/id-header]
                        correlation-id)))))
