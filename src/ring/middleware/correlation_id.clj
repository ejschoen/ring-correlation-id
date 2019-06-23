(ns ring.middleware.correlation-id
  (import [java.util UUID])
  )

(def id-header "X-Correlation-Id")

(def ^:dynamic *correlation-id* nil)

(defn get-header-correlation-id
  [req]
  (get-in req [:header id-header]))

(defn wrap-correlation-id
  [handler]
  (fn [req]
    (if (get-header-correlation-id req)
      (binding [*correlation-id* (get-header-correlation-id req)]
        (update-in (handler req)
                   [:headers id-header] #(or % *correlation-id*)))
      (binding [*correlation-id*  (str (UUID/randomUUID))]
        (update-in (handler (assoc-in req [:headers id-header] *correlation-id*))
                   [:headers id-header] #(or % *correlation-id*))))))

