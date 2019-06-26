(ns ring.middleware.test-correlation-id
  (:require [clojure.test :refer :all]
            [ring.middleware.correlation-id :refer :all])
  )

(defn- handler
  [req]
  {:status 200 :body (format "%s %s" *correlation-id* (get (:headers req) id-header ))})

(deftest wrap-correlation-id-test
  (let [resp ((wrap-correlation-id handler) {})]
    (is (not-empty (get-in resp [:headers id-header])))
    (is (not-empty (:body resp)))
    (is (= (format "%s %s" (get-in resp [:headers id-header]) (get-in resp [:headers id-header]))
           (:body resp)))))
