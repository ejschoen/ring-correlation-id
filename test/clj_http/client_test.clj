(ns clj-http.client-test
  (:require [clojure.pprint :as pprint]
            [clojure.test :refer :all])
  (:require [clj-http.client :as c])
  (:require [ring.middleware.correlation-id :as ring-correlation-id]
            [clj-http.middleware.correlation-id :as client-correlation-id])
  (:use [clj-http.fake]))

(deftest test-clj-http-client
  (with-global-fake-routes-in-isolation
    {"http://test.com" (fn [req]
                         (let [correlation-id (get-in req [:headers ring-correlation-id/id-header])]
                           {:status 200
                            :body correlation-id
                            :headers {ring-correlation-id/id-header correlation-id}}))}
    (c/with-additional-middleware [#'client-correlation-id/wrap-correlation-id]
      (let [response (c/get "http://test.com")]
        (is (not-empty (get-in response [:headers ring-correlation-id/id-header])))))))
                                         
  
