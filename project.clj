(defproject org.clojars.ejschoen/ring-correlation-id "0.5.0-SNAPSHOT"
  :description "Correlation ID tracing for distributed systems using ring and clj-http"
  :url "https://github.com/ejschoen/ring-correlation-id.git"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies []
  :plugins [[lein-eftest "0.5.9"]]
  :eftest {:multithread? false}
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.8.0"]
                                  [org.clojure/core.cache "1.0.207"]
                                  [ring "1.6.3"]
                                  [com.taoensso/encore "2.126.2"]
                                  [com.taoensso/timbre "4.10.0"]
                                  [clj-http "3.10.0"]
                                  [clj-http-fake "1.0.3"]
                                  [org.clojars.ejschoen/clj-telemetry "0.3.0-SNAPSHOT" ]
                                  [cheshire "5.9.0"]
                                  ]}})
