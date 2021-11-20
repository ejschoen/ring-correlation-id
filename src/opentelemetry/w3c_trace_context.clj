(ns opentelemetry.w3c-trace-context
  (:require [clojure.string :as str]
            [clojure.java.io :as io])
  (:require [taoensso.timbre :refer [errorf warnf]])
  (:require [cheshire.core :as cheshire])
  (:import [java.io ByteArrayInputStream]
           [java.util Base64 Base64$Decoder Base64$Encoder]))


(def ^:private traceparent-pattern-generic
  #"(?<version>[a-f\d]{2})-.+")

(def ^:private traceparent-pattern-by-version
  {"00" #"(?<version>[a-f\d]{2})-(?<trace>[a-f\d]{32})-(?<parent>[a-f\d]{16})-(?<flags>[a-f\d]{2})"
   nil #"(?<version>[a-f\d]{2})-(?<trace>[a-f\d]{32})-(?<parent>[a-f\d]{16})-(?<flags>[a-f\d]{2}(?:-.*)?)"})
   

(defn- re-matches-with-named-groups
  [pattern string & groups]
  (let [^java.util.regex.Matcher matcher (re-matcher pattern string)]
    (if (.matches matcher)
      (reduce (fn [m next]
                (if (vector? next)
                  (assoc m (first next) (.group matcher (second next)))
                  (assoc m (keyword next) (.group matcher (name next)))))
              {}
              groups)
      (do (errorf "Bad traceparent: %s" string)
          nil))))

(defmulti ^:private validate (fn [m] (:version m)))

(defmethod validate :default [m]
  (when (and (not= (:version m) "ff")
             (not= (:trace-id m) "00000000000000000000000000000000")
             (not= (:parent-id m) "0000000000000000"))
    m))

(defmethod validate "00" [m]
  ((.getMethod validate :default) m))


(defmulti ^:private parse (fn [m] (:version m)))

(defmethod parse :default [m]
  m)

(defmethod parse "00" [m]
  (let [flag-value (Integer/parseInt (:trace-flags m) 16)]
    (assoc m
           :trace-flags-value flag-value
           :sampled? (= 1 (bit-and flag-value 2r1)))))

         

(defmulti ^:private decode-vendorstate (fn [vendor value] vendor))

(defmethod decode-vendorstate :default [vendor value]
  nil)

(defmethod decode-vendorstate "rcid" [vendor value]
  (let [b64 (.decode (Base64/getDecoder) (.getBytes value))]
    (with-open [reader (io/reader b64)]
      (cheshire/parse-stream reader true))))

(defn parse-traceparent
  "Parse a traceparent header value.  Return a map containing at least :version.
   For version 00 of traceparent, the map will contain :trace-id, :parent-id, and :trace-flags."
  [traceparent]
  (when traceparent
    (if-let [{:keys [version]} (re-matches-with-named-groups traceparent-pattern-generic traceparent :version)]
      (let [pattern (get traceparent-pattern-by-version version)]
        (if pattern
          (-> (re-matches-with-named-groups pattern traceparent
                                            :version
                                            [:trace-id "trace"]
                                            [:parent-id "parent"]
                                            [:trace-flags "flags"])
              validate
              parse)
          (do (warnf "Unknown version %s" version)
              (-> (re-matches-with-named-groups (get traceparent-pattern-by-version nil)
                                                traceparent
                                                :version
                                                [:trace-id "trace"]
                                                [:parent-id "parent"]
                                                [:trace-flags "flags"])
                  validate
                  parse))))
      (errorf "Bad traceparent: %s" traceparent))))

(defn parse-tracestate
  [tracestate]
  (when tracestate
    (let [members (str/split tracestate #"\s*,\s*")]
      (reduce (fn [state member]
                (let [[vendor value] (str/split member #"\s*=\s*")
                      vendor-state (decode-vendorstate vendor value)]
                  (if vendor-state
                    (assoc state vendor vendor-state (keyword vendor) vendor-state)
                    state)))
              {}
              (str/split tracestate #"\s*,\s*")))))

(defmulti ^:private encode-vendorstate (fn [vendor state] vendor))

(defmethod encode-vendorstate :default [vendor state] nil)

(defmethod encode-vendorstate :rcid [vendor state]
  (.encodeToString (Base64/getEncoder) (.getBytes (cheshire/generate-string state))))

(defn encode-tracestate
  [state]
  (str/join ","
            (for [[vendor vendor-state] state]
              (format "%s=%s" (name vendor) (encode-vendorstate vendor vendor-state)))))
