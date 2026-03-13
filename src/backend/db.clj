(ns backend.db
  (:require [clojure.edn :as edn]))

(defn- load-edn [path]
  (edn/read-string (slurp path)))

(defonce posts     (atom (load-edn "resources/posts.edn")))
(defonce users     (atom (load-edn "resources/users.edn")))
(defonce shortened (atom (load-edn "resources/shortened.edn")))

(defn save-posts!     [] (spit "resources/posts.edn"     (pr-str @posts)))
(defn save-users!     [] (spit "resources/users.edn"     (pr-str @users)))
(defn save-shortened! [] (spit "resources/shortened.edn" (pr-str @shortened)))