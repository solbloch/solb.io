(defproject solb "0.1.0-SNAPSHOT"
  :description "little website server"
  :url "https://solb.io"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [compojure "1.6.1"]
                 [hiccup "1.0.5"]
                 [http-kit "2.3.0"]
                 [buddy "2.0.0"]
                 [ring/ring-defaults "0.3.2"]
                 [ring/ring-json "0.4.0"]
                 [markdown-clj "1.12.7"]
                 [clj-rss "0.2.3"]]
  :main solb.handler
  :plugins [[lein-ring "0.12.5"]]
  :ring {:handler solb.handler/app}
  :profiles
  {:dev {:dependencies [[javax.servlet/servlet-api "2.5"]
                        [ring/ring-mock "0.3.2"]]}})
