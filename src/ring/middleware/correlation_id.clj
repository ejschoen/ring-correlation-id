(ns ring.middleware.correlation-id
  (:require [taoensso.timbre :as timbre])
  (:import [java.util UUID])
  )

(def id-header "x-correlation-id")

(def ^:dynamic *correlation-id* nil)

(defn get-header-correlation-id
  [req]
  (get-in req [:headers id-header]))

(defn wrap-correlation-id
  [handler]
  (fn [req]
    (if-let [correlation-id (get-header-correlation-id req)]
      (binding [*correlation-id* correlation-id]
        (timbre/with-context {:correlation-id *correlation-id*}
          (update-in (handler req)
                     [:headers id-header] #(or % *correlation-id*))))
      (let [new-id (str (UUID/randomUUID))]
        (timbre/debugf "RING MIDDLEWARE: New Correlation ID: %s" new-id)
        (binding [*correlation-id*  new-id]
          (timbre/with-context {:correlation-id new-id}
            (update-in (handler (assoc-in req [:headers id-header] *correlation-id*))
                       [:headers id-header] #(or % *correlation-id*))))))))

