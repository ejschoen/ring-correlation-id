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
  (:import [io.opentelemetry.context Context Scope]
           [io.opentelemetry.context.propagation ContextPropagators
            TextMapPropagator TextMapGetter TextMapSetter ]
           [io.opentelemetry.api.common AttributeKey]
           [io.opentelemetry.api.trace Span])
  (:import [io.opentelemetry.sdk.common CompletableResultCode]
           [io.opentelemetry.sdk.trace.export SimpleSpanProcessor SpanExporter]))

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

;;; ---------------------------------------------------------------------------
;;; with-span, and carrying a trace across a thread boundary.
;;;
;;; These read the spans back instead of trusting Span/current, so that
;;; parentage and Scope restoration are asserted on what was actually recorded.
;;; The library has no in-memory-exporter dependency and does not need one: a
;;; SpanExporter is three methods, and adding
;;; io.opentelemetry/opentelemetry-exporters-inmemory would put an
;;; OpenTelemetry 0.9.1 artifact on a 1.x classpath.

(defn- collecting-exporter
  "A SpanExporter that appends every finished span to the spans atom."
  [spans]
  (reify SpanExporter
    (export [_ exported] (swap! spans into exported) (CompletableResultCode/ofSuccess))
    (flush [_] (CompletableResultCode/ofSuccess))
    (shutdown [_] (CompletableResultCode/ofSuccess))))

(defmacro with-collected-spans
  "Re-register this process's OpenTelemetry with a SimpleSpanProcessor feeding a
   collecting exporter, so the body's finished spans land in the spans atom as
   they end.  GlobalOpenTelemetry takes one registration per JVM, so the
   fixture's instance has to be reset first; the fixture resets it again after
   the test."
  [spans & body]
  `(do (reset-open-telemetry!)
       (create-open-telemetry!
        {:sampler "on"
         :span-processor (SimpleSpanProcessor/create (collecting-exporter ~spans))
         :tracer-attributes {"service.name" "ring-correlation-id-test"}})
       (set-tracer! (get-tracer "test.tracing"))
       ~@body))

(defn- span-named
  [spans name]
  (some #(when (= name (.getName %)) %) spans))

(defn- root-span
  "Start a span with no parent, independently of with-span, so a test that
   asserts what with-span does with the current span does not depend on
   with-span to establish one."
  [name]
  (-> (.spanBuilder (get-tracer) name)
      (.setNoParent)
      (.startSpan)))

(defn- on-another-thread
  "Run f on a brand new thread and return its value (rethrowing what it threw).
   A new thread rather than a future: OpenTelemetry's current context is a Java
   thread-local, and a pooled thread could be carrying one an earlier test left."
  [f]
  (let [result (promise)
        thread (Thread. ^Runnable (fn [] (deliver result (try {:value (f)}
                                                              (catch Throwable e {:threw e})))))]
    (.start thread)
    (.join thread)
    (if-let [e (:threw @result)]
      (throw e)
      (:value @result))))

(defn- exception-event
  [span]
  (some #(when (= "exception" (.getName %)) %) (.getEvents span)))

(deftest test-with-span-parents-on-the-current-span
  ;; Criterion 1, child case, plus Scope restoration.
  (let [spans (atom [])]
    (with-collected-spans spans
      (let [^Span outer (root-span "outer")]
        (with-open [^Scope scope (.makeCurrent outer)]
          (with-span "inner"
            (is (= (.getTraceId (.getSpanContext outer))
                   (.getTraceId (.getSpanContext ((interface-static-call Span/current)))))
                "the body runs on the outer span's trace")
            (is (not= (.getSpanId (.getSpanContext outer))
                      (.getSpanId (.getSpanContext ((interface-static-call Span/current)))))
                "in a span of its own, not the outer one"))
          (is (= (.getSpanId (.getSpanContext outer))
                 (.getSpanId (.getSpanContext ((interface-static-call Span/current)))))
              "and the outer span is current again once the body is done"))
        (.end outer))
      (let [inner (span-named @spans "inner")
            outer (span-named @spans "outer")]
        (is inner "the inner span was recorded")
        (is outer "the outer span was recorded")
        (is (= (.getTraceId outer) (.getTraceId inner))
            "the inner span carries the outer span's trace id")
        (is (= (.getSpanId outer) (.getParentSpanId inner))
            "and names the outer span as its parent")))))

(deftest test-with-span-roots-when-no-valid-span-is-current
  ;; Criterion 1, root case.
  (let [spans (atom [])]
    (with-collected-spans spans
      (is (not (.isValid (.getSpanContext ((interface-static-call Span/current)))))
          "precondition: no valid span is current on this thread")
      (is (= :body-value (with-span "orphan" :body-value))
          "with-span returns what its body returns")
      (is (not (.isValid (.getSpanContext ((interface-static-call Span/current)))))
          "and leaves no valid span current behind it")
      (let [orphan (span-named @spans "orphan")]
        (is orphan "the span was recorded")
        (is (= "0000000000000000" (.getParentSpanId orphan))
            "with no parent")))))

(deftest test-with-span-records-and-rethrows-exceptions
  ;; Criterion 1, exception case.
  (let [spans (atom [])]
    (with-collected-spans spans
      (is (thrown-with-msg? Exception #"Boom!"
                            (with-span "throwing" (throw (Exception. "Boom!"))))
          "the exception is rethrown to the caller")
      (is (not (.isValid (.getSpanContext ((interface-static-call Span/current)))))
          "and the Scope is closed even on the way out through a throw")
      (let [span (span-named @spans "throwing")]
        (is span "the span was ended and recorded")
        (let [event (exception-event span)]
          (is event "the exception was recorded on the span")
          (is (= "Boom!" (.get (.getAttributes event)
                               ((interface-static-call AttributeKey/stringKey String)
                                "exception.message"))))
          (is (= true (.get (.getAttributes event)
                            ((interface-static-call AttributeKey/booleanKey String)
                             "exception.escaped")))
              "marked as escaping the span"))))))

(deftest test-with-span-injects-traceparent-into-clj-http
  ;; Criterion 2.
  (let [spans (atom [])]
    (with-collected-spans spans
      (testing "a clj-http call inside with-span, with no explicit middleware wrapper"
        (let [captured (atom :unset)]
          (with-global-fake-routes-in-isolation
            {"http://test.com" (fn [req]
                                 (reset! captured (get-in req [:headers "traceparent"]))
                                 {:status 200 :body "Hello"})}
            (with-span "http-span"
              (c/get "http://test.com")))
          (let [traceparent (parse-traceparent @captured)
                span (span-named @spans "http-span")]
            (is span)
            (is traceparent
                "with-span installs the clj-http telemetry middleware for its body")
            (is (= (.getTraceId span) (:trace-id traceparent))
                "the request carries the span's trace id")
            (is (= (.getSpanId span) (:parent-id traceparent))
                "and names the span as the parent of what the callee starts"))))
      (testing "a clj-http call outside any span"
        (let [captured (atom :unset)]
          (with-global-fake-routes-in-isolation
            {"http://test.com" (fn [req]
                                 (reset! captured (get-in req [:headers "traceparent"]))
                                 {:status 200 :body "Hello"})}
            (clj-http-with-telemetry-span-middleware
             (c/get "http://test.com")))
          (is (nil? @captured)
              "no valid span current means nothing is injected"))))))

(deftest test-trace-context-round-trip-across-threads
  ;; Criterion 4.
  (let [spans (atom [])]
    (with-collected-spans spans
      (testing "captured under a span, made current on another thread"
        (let [headers (atom nil)]
          (with-span "planner"
            (reset! headers (current-trace-context)))
          (is (get @headers "traceparent")
              "current-trace-context yields W3C headers")
          (let [planner (span-named @spans "planner")]
            (is planner)
            (is (.contains ^String (get @headers "traceparent") (.getTraceId planner))
                "carrying the trace id of the span that was current")
            (on-another-thread
             (fn []
               (is (not (.isValid (.getSpanContext ((interface-static-call Span/current)))))
                   "a fresh thread inherits no trace of its own")
               (with-trace-context @headers
                 (is (.isValid (.getSpanContext ((interface-static-call Span/current))))
                     "with-trace-context makes the captured context current")
                 (with-span "worker" nil))
               (is (not (.isValid (.getSpanContext ((interface-static-call Span/current)))))
                   "and closes its Scope afterwards")))
            (let [worker (span-named @spans "worker")]
              (is worker)
              (is (= (.getTraceId planner) (.getTraceId worker))
                  "a span started on the other thread joins the captured trace")
              (is (= (.getSpanId planner) (.getParentSpanId worker))
                  "as a child of the span that was captured")))))
      (testing "with no valid span current"
        (is (nil? (current-trace-context))
            "there is nothing to capture")
        (is (= :ran (with-trace-context nil :ran))
            "and with-trace-context of nil runs the body unchanged")
        (is (= :ran (with-trace-context (current-trace-context) :ran)))))))

(deftest test-trace-context-survives-a-keywordizing-json-round-trip
  ;; Criterion 4, the shape the headers actually arrive in.  A carrier that has
  ;; been through JSON usually comes back with keyword names -- conduit's
  ;; follower parses a stolen work item with cheshire/parse-string ... true --
  ;; and a getter that only looked up the propagator's string key would find
  ;; nothing and lose the trace without saying so.
  (let [spans (atom [])]
    (with-collected-spans spans
      (let [headers (atom nil)]
        (with-span "planner"
          (reset! headers (current-trace-context)))
        (let [keywordized (into {} (map (fn [[k v]] [(keyword k) v])) @headers)
              planner (span-named @spans "planner")]
          (is (every? keyword? (keys keywordized))
              "precondition: every header name is a keyword")
          (is (get keywordized :traceparent)
              "precondition: the traceparent survived the keywordizing")
          (is planner)
          (on-another-thread
           (fn []
             (with-trace-context keywordized
               (is (.isValid (.getSpanContext ((interface-static-call Span/current))))
                   "keyword header names are extracted just like string ones")
               (with-span "keywordized-worker" nil))))
          (let [worker (span-named @spans "keywordized-worker")]
            (is worker)
            (is (= (.getTraceId planner) (.getTraceId worker))
                "the span started on the other thread joins the captured trace")
            (is (= (.getSpanId planner) (.getParentSpanId worker))
                "as a child of the span that was captured")))))))

(deftest test-wrap-with-current-context-carries-the-span-to-another-thread
  ;; Criterion 4, the in-process (executor) half.
  (let [spans (atom [])]
    (with-collected-spans spans
      (let [task (atom nil)]
        (with-span "submitter"
          (reset! task (wrap-with-current-context
                        (fn [] (with-span "task" :done)))))
        (is (= :done (on-another-thread @task))
            "the wrapped fn returns what it returns")
        (let [submitter (span-named @spans "submitter")
              task-span (span-named @spans "task")]
          (is submitter)
          (is task-span)
          (is (= (.getTraceId submitter) (.getTraceId task-span))
              "the task runs on the submitter's trace")
          (is (= (.getSpanId submitter) (.getParentSpanId task-span))
              "as a child of the submitting span"))))))

(deftest test-get-tracer-is-not-poisoned-by-a-call-before-registration
  ;; get-tracer memoizes, and get-open-telemetry answers the NOOP instance
  ;; until something is registered.  A single get-tracer call made before
  ;; create-open-telemetry! used to cache a noop tracer for the life of the
  ;; process, and every with-span after it started an invalid span forever -
  ;; silently: no exception, no traceparent, no traceID= on any log line.
  ;; Boot order made it latent rather than harmless.
  ;;
  ;; Deliberately NOT written with with-collected-spans: that resets the
  ;; process first, which would clear the cached tracer by itself and the test
  ;; would pass with the fix removed.  create-open-telemetry! has to do it.
  (let [spans (atom [])]
    (reset-open-telemetry!)
    (let [early (get-tracer "too.early")]
      (is (some? early) "the early call still gets a usable (noop) tracer")
      (is (not (.isValid (.getSpanContext (.startSpan (.spanBuilder early "noop")))))
          "precondition: a tracer from the unregistered instance makes invalid spans"))
    ;; No reset in between - registering is what must drop the cached tracer.
    (create-open-telemetry!
     {:sampler "on"
      :span-processor (SimpleSpanProcessor/create (collecting-exporter spans))
      :tracer-attributes {"service.name" "ring-correlation-id-test"}})
    (with-span "after-registration"
      (is (.isValid (.getSpanContext ((interface-static-call Span/current))))
          "a span started after registration is valid, not the cached noop's"))
    (is (span-named @spans "after-registration")
        "and it reached the exporter")))

(deftest test-set-open-telemetry-also-drops-the-cached-tracer
  (reset-open-telemetry!)
  (let [early (get-tracer "too.early")]
    (is (identical? early (get-tracer))
        "precondition: get-tracer memoizes")
    (set-open-telemetry! (create-open-telemetry!
                          {:sampler "on" :tracer-attributes {"service.name" "test"}}))
    (is (not (identical? early (get-tracer)))
        "installing an instance drops the tracer cached from the noop")))
