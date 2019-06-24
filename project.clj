(defproject org.clojars.ejschoen/ring-correlation-id "0.4.0"
  :description "Correlation ID tracing for distributed systems using ring and clj-http"
  :url "https://github.com/ejschoen/ring-correlation-id.git"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies []
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.8.0"]
                                  [ring "1.6.3"]
                                  [com.taoensso/timbre "4.0.2"]
                                  [clj-http "3.7.0"]
                                  [clj-http-fake "1.0.3"]]}}
  )
