(ns opentelemetry.w3c-trace-context-test
  (:require [clojure.test :refer :all]
            [opentelemetry.w3c-trace-context :refer :all]))

(deftest test-parse-traceparent
  (let [traceparent "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
        result (parse-traceparent traceparent)]
    (is result)
    (is (= "00" (:version result)))
    (is (= "0af7651916cd43dd8448eb211c80319c" (:trace-id result)))
    (is (= "b7ad6b7169203331" (:parent-id result)))
    (is (= "01" (:trace-flags result)))
    (is (= 1 (:trace-flags-value result)))
    (is (:sampled? result)))
  (are [traceparent] (= nil (parse-traceparent traceparent))
    nil
    "ff-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
    "00-00000000000000000000000000000000-b7ad6b7169203331-01"
    "00-0af7651916cd43dd8448eb211c80319c-0000000000000000-01"))


(deftest test-tracestate
  (let [encoded (encode-tracestate {:rcid {:a 1 :b 2}})]
    (is (string? encoded))
    (is (re-matches #"rcid=[^,]+" encoded))
    (let [decoded (parse-tracestate encoded)]
      (is (= {:a 1 :b 2} (:rcid decoded))))))

