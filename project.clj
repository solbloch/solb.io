(defproject solb "0.1.0-SNAPSHOT"
  :description "little website server"
  :url "https://solb.io"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [compojure "1.6.1"]
                 [hiccup "1.0.5"]
                 [http-kit "2.2.0"]
                 [buddy "2.0.0"]
                 [ring/ring-defaults "0.3.2"]
                 [ring/ring-json "0.4.0"]
                 [org.clojure/java.jdbc "0.7.8"]
                 [org.postgresql/postgresql "42.2.5.jre7"]
                 [com.cemerick/url "0.1.1"]
                 [honeysql "0.9.4"]]
  :main solb.handler
  :plugins [[lein-ring "0.12.4"]
            [cider/cider-nrepl "0.18.0"]]
  :ring {:handler solb.handler/app}
  :profiles
  {:dev {:dependencies [[javax.servlet/servlet-api "2.5"]
                        [ring/ring-mock "0.3.2"]]}})
