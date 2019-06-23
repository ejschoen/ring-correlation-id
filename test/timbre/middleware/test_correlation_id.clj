(ns timbre.middleware.test-correlation-id
  (:require [clojure.test :refer :all]
            [timbre.middleware.correlation-id :as timbre-correlation-id]
            [ring.middleware.correlation-id :refer :all]
            [taoensso.timbre :refer [infof with-merged-config]])
  )

(def log (atom nil))

(defn- handler
  [req]
  (infof "Hi!  I am Timbre, and I am called with correlation id %s" *correlation-id*)
  {:status 200 :body (format "%s %s" *correlation-id* (get (:headers req) "X-Correlation-Id" ))})

(deftest wrap-correlation-id-test
  (with-merged-config {:appenders {:test-appender {:enabled? true :async? false :fn (fn [data] (reset! log data))}}}
    (timbre-correlation-id/with-correlation-id-middleware
      (let [resp ((wrap-correlation-id handler) {})]
        (is @log)
        (is (:correlation-id @log))
        ))))
