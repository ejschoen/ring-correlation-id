(ns opentelemetry.middleware.test
  (:require [clojure.pprint :as pprint]
            [clojure.test :refer :all])
  (:require [clj-http.client :as c])
  (:use [taoensso.timbre :exclude [report]])
  (:use [opentelemetry.middleware])
  (:use [opentelemetry.w3c-trace-context])
  (:use [clj-http.fake])
  (:require [timbre.middleware.correlation-id :as tcid])
  (:import [io.opentelemetry.context Context]
           [io.opentelemetry.context.propagation ContextPropagators
            TextMapPropagator TextMapGetter TextMapSetter ]
           [io.opentelemetry.api.trace Span]))

(use-fixtures :each (fn [f]
                      (create-open-telemetry! {:sampler "on" :tracer-attributes {"service.name" "i2kconnect"}})
                      (set-tracer! (get-tracer "test.tracing"))
                      (try (f)
                           (finally (reset-open-telemetry!)))))

(deftest test-clj-http-telemetry-middleware
  (testing "with no span context"
    (with-global-fake-routes-in-isolation
      {"http://test.com" (fn [req]
                           ;;(pprint/pprint req)
                           (let [traceparent (parse-traceparent (get-in req [:headers "traceparent"]))]
                             (is (nil? traceparent)))
                           {:status 200
                            :body "Hello"})}
      (clj-http-with-telemetry-span-middleware
       (is (not (.isValid (.getSpanContext (Span/current)))))
       (c/get "http://test.com"))))
  (testing "with active span context"
    (with-span "test-span"
      (with-global-fake-routes-in-isolation
        {"http://test.com" (fn [req]
                             ;;(pprint/pprint req)
                             (let [traceparent (parse-traceparent (get-in req [:headers "traceparent"]))]
                               (is (= "00" (:version traceparent)))
                               (is (:trace-id traceparent))
                               (is (not= (:trace-id traceparent) "00000000000000000000000000000000"))
                               (is (:parent-id traceparent))
                               (is (not= (:parent-id traceparent) "0000000000000000"))
                               (is (:sampled? traceparent)))
                             {:status 200
                              :body "Hello"})}
        (clj-http-with-telemetry-span-middleware
         (c/get "http://test.com"))))))
                                         
  
(defn- output-fn-wrapper
  ([data] (output-fn-wrapper nil data))
  ([opts data]
   (is (= (:trace-id data) (.getTraceId (.getSpanContext (Span/current)))))
   (is (= (:span-id data) (.getSpanId (.getSpanContext (Span/current)))))
   (is (= (:trace-flags data) (.asHex (.getTraceFlags (.getSpanContext (Span/current))))))
   (timbre-output-fn opts data)))

(deftest test-ring-telemetry-middleware
  (testing "creates top level context"
    (let [handler (fn [req]
                    (timbre-with-telemetry-span-middleware {:output-fn output-fn-wrapper}
                     (is (.getTraceId (.getSpanContext (Span/current))))
                     (is (.getParentSpanContext (Span/current)))
                     (is (= "00000000000000000000000000000000"
                            (.getTraceId (.getParentSpanContext (Span/current)))))
                     (is (.isSampled (.getSpanContext (Span/current))))
                     (is (.isValid (.getSpanContext (Span/current))))
                     (info "**** Hello world")
                     {:status 200 :body "Hello"}))
          resp ((ring-wrap-telemetry-span handler)
                {:headers {}})]
      ))
  (testing "creates child context from parent in headers"
    (let [handler (fn [req]
                    (timbre-with-telemetry-span-middleware {:output-fn output-fn-wrapper}
                     (is (= "2149c7c507824641b6bd38e8fe548bed"
                            (.getTraceId (.getSpanContext (Span/current)))))
                     (is (= "7c34f6f8ab7c5691"
                            (.getSpanId (.getParentSpanContext (Span/current)))))
                     (is (.isSampled (.getSpanContext (Span/current))))
                     (is (.isValid (.getSpanContext (Span/current))))
                     (info "**** Hello world")
                     {:status 200 :body "Hello"}))
          resp ((ring-wrap-telemetry-span handler)
                {:headers {"traceparent" "00-2149c7c507824641b6bd38e8fe548bed-7c34f6f8ab7c5691-01"}})]
      )))

(deftest test-span-propagation
  (testing "with active span context"
    (with-span "test-span"
      (let [spancontext (.getSpanContext (Span/current))]
        (with-global-fake-routes-in-isolation
          {"http://test.com" (ring-wrap-telemetry-span
                              (fn [req]
                                ;;(pprint/pprint req)
                                (let [traceparent (parse-traceparent (get-in req [:headers "traceparent"]))]
                                  (is (= "00" (:version traceparent)))
                                  (is (:trace-id traceparent))
                                  (is (= (:trace-id traceparent) (.getTraceId spancontext)))
                                  (is (:parent-id traceparent))
                                  (is (= (:parent-id traceparent) (.getSpanId spancontext)))
                                  (is (:sampled? traceparent)))
                                {:status 200
                                 :body (:body (c/get "http://subtest.com"))}))
           "http://subtest.com" (ring-wrap-telemetry-span
                                 (fn [req]
                                   (let [traceparent (parse-traceparent (get-in req [:headers "traceparent"]))]
                                     (is (= (:trace-id traceparent) (.getTraceId spancontext)))
                                     (is (not= (:parent-id traceparent) (.getSpanId spancontext)))
                                     (is (not= (:parent-id traceparent) "0000000000000000")))
                                   {:status 200
                                    :body "Goodbye"}))
           }
          (clj-http-with-telemetry-span-middleware
           (c/get "http://test.com")))))))
