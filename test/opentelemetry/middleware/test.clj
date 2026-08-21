(ns opentelemetry.middleware.test
  (:require [clojure.pprint :as pprint]
            [clojure.test :refer :all])
  (:require [clj-http.client :as c])
  (:use [taoensso.timbre :exclude [report]])
  (:use [opentelemetry.middleware])
  (:use [opentelemetry.w3c-trace-context])
  (:use [clj-http.fake])
  (:require [timbre.middleware.correlation-id :as tcid])
  (:import [io.opentelemetry.api GlobalOpenTelemetry OpenTelemetry])
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
       (is (not (.isValid (.getSpanContext ((interface-static-call Span/current))))))
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
   (is (= (get-in data [:context :trace-id]) (.getTraceId (.getSpanContext ((interface-static-call Span/current))))))
   (is (= (get-in data [:context :span-id]) (.getSpanId (.getSpanContext ((interface-static-call Span/current))))))
   (is (= (get-in data [:context :trace-flags]) (.asHex (.getTraceFlags (.getSpanContext ((interface-static-call Span/current)))))))
   (timbre-output-fn opts data)))

(deftest test-ring-telemetry-middleware
  (testing "creates top level context"
    (let [handler (fn [req]
                    (timbre-with-telemetry-span-middleware {:output-fn output-fn-wrapper}
                     (is (.getTraceId (.getSpanContext ((interface-static-call Span/current)))))
                     (is (.getParentSpanContext ((interface-static-call Span/current))))
                     (is (= "00000000000000000000000000000000"
                            (.getTraceId (.getParentSpanContext ((interface-static-call Span/current))))))
                     (is (.isSampled (.getSpanContext ((interface-static-call Span/current)))))
                     (is (.isValid (.getSpanContext ((interface-static-call Span/current)))))
                     (info "**** Hello world")
                     {:status 200 :body "Hello"}))
          resp ((ring-wrap-telemetry-span handler)
                {:headers {}})]
      ))
  (testing "creates child context from parent in headers"
    (let [handler (fn [req]
                    (timbre-with-telemetry-span-middleware {:output-fn output-fn-wrapper}
                     (is (= "2149c7c507824641b6bd38e8fe548bed"
                            (.getTraceId (.getSpanContext ((interface-static-call Span/current))))))
                     (is (= "7c34f6f8ab7c5691"
                            (.getSpanId (.getParentSpanContext ((interface-static-call Span/current))))))
                     (is (.isSampled (.getSpanContext ((interface-static-call Span/current)))))
                     (is (.isValid (.getSpanContext ((interface-static-call Span/current)))))
                     (info "**** Hello world")
                     {:status 200 :body "Hello"}))
          resp ((ring-wrap-telemetry-span handler)
                {:headers {"traceparent" "00-2149c7c507824641b6bd38e8fe548bed-7c34f6f8ab7c5691-01"}})]
      )))

(deftest test-ring-telemetry-middleware-with-exception
  (testing "creates top level context"
    (is (thrown-with-msg? Exception #"Boom!"
                              (let [handler (fn [req] (throw (Exception. "Boom!")))
                                    resp ((ring-wrap-telemetry-span handler)
                                          {:headers {}})]
                                )))))

(deftest test-span-propagation
  (testing "with active span context"
    (with-span "test-span"
      (let [spancontext (.getSpanContext ((interface-static-call Span/current)))]
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

(deftest test-create-open-telemetry-adopts-a-global-claimed-elsewhere
  ;; GlobalOpenTelemetry takes one registration per JVM, and this namespace is
  ;; not the only thing that claims it.  Solr 10's OpenTelemetryConfigurator
  ;; calls GlobalOpenTelemetry.set from CoreContainer.load, which Solr 9 never
  ;; did.  Whichever side lost that race used to get an IllegalStateException
  ;; and leave _ot nil, so every later call retried and threw again -- each time
  ;; printing the winner's stack, because OpenTelemetry attaches the first
  ;; registration to the exception as its cause.
  (let [ot-atom (var-get #'opentelemetry.middleware/_ot)]
    (reset-open-telemetry!)
    ;; Stand in for Solr: claim the global without going through this namespace.
    (GlobalOpenTelemetry/set (OpenTelemetry/propagating (ContextPropagators/noop)))
    (reset! ot-atom nil)
    (let [adopted (create-open-telemetry! {:sampler "on"
                                           :tracer-attributes {"service.name" "test"}})]
      (is (some? adopted)
          "losing the race must still yield an OpenTelemetry, not an exception")
      (is (identical? adopted (GlobalOpenTelemetry/get))
          "and it must be the instance that is actually registered")
      (is (some? @ot-atom)
          "which must be cached, so a later call does not retry the registration"))
    (is (identical? (GlobalOpenTelemetry/get)
                    (create-open-telemetry! {:sampler "on"}))
        "so a second call is a no-op rather than a second failure")))

(deftest test-create-open-telemetry-registers-once-under-contention
  ;; buildAndRegisterGlobal used to run inside swap!, whose function is re-run
  ;; whenever the compare-and-set loses.  Two threads arriving together each
  ;; registered the global, and the loser threw.
  (reset-open-telemetry!)
  (let [attempts (->> (repeatedly 8 #(future (create-open-telemetry!
                                              {:sampler "on"
                                               :tracer-attributes {"service.name" "test"}})))
                      doall
                      (mapv #(try (deref %) (catch Exception e e))))]
    (is (every? (partial instance? OpenTelemetry) attempts)
        "no caller may see the registration fail")
    (is (apply = attempts)
        "and every caller must get the one registered instance")))
