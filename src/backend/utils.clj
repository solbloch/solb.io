(ns backend.utils
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [backend.db :as db]))

(defonce characters "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")

(def file-storage-location "resources/uploads/")

(defn- configured-base-url []
  (let [configured (some-> (System/getenv "SOLB_BASE_URL") str/trim)]
    (when (seq configured) configured)))

(defn- localhost-request? [req]
  (let [server-name (str/lower-case (str (or (:server-name req) "")))]
    (contains? #{"localhost" "127.0.0.1" "::1"} server-name)))

(defn- short-url-base [req]
  (or (configured-base-url)
      (if (localhost-request? req)
        "http://localhost:3000"
        "https://solb.io")))

(defn- build-short-url [req id]
  (str (short-url-base req) "/" id))

(defn random-chars [n]
  (->> (repeatedly #(rand-nth characters))
       (take n)
       (reduce str)))

(defn generate-token []
  (random-chars 30))

(defn unique-id []
  (let [new-id (random-chars (+ 4 (rand-int 4)))]
    (if (some #(= new-id (:id %)) @db/shortened)
      (unique-id)
      new-id)))

(defn validate-token-return-username [token]
  (:username (first (filter #(= token (:token %)) @db/users))))

(defn file-upload [req]
  (let* [id       (unique-id)
         token    (get (req :params) "token")
         data     (str file-storage-location id)]
    (when-let [username (validate-token-return-username token)]
      (io/make-parents data)
      (cond
        (string? (get (req :params) "file"))
        (spit data (get (req :params) "file"))
        :else
        (when (< (:size (get (req :params) "file")) 20000000)
          (io/copy (:tempfile (get (req :params) "file"))
                   (io/file data))))
      (swap! db/shortened conj
             {:id id :type (get (req :params) "type") :data "" :username username})
      (db/save-shortened!)
            (build-short-url req id))))

(defn editor-image-upload [req]
  (let [file-param (or (get-in req [:params "image"])
                       (get-in req [:params :image]))]
    (cond
      (nil? file-param)
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body "{\"error\":\"missing image\"}"}

      (string? file-param)
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body "{\"error\":\"invalid image upload\"}"}

      :else
      (let [content-type (or (:content-type file-param) "")
            size         (or (:size file-param) 0)
            image?       (re-find #"^image/" content-type)]
        (cond
          (not image?)
          {:status 400
           :headers {"Content-Type" "application/json"}
           :body "{\"error\":\"file must be an image\"}"}

          (>= size 20000000)
          {:status 400
           :headers {"Content-Type" "application/json"}
           :body "{\"error\":\"image too large\"}"}

          :else
          (let [id   (unique-id)
                path (str file-storage-location id)
                url  (build-short-url req id)]
            (io/make-parents path)
            (io/copy (:tempfile file-param) (io/file path))
            (swap! db/shortened conj
                   {:id id :type content-type :data "" :username "sol"})
            (db/save-shortened!)
            {:status 200
             :headers {"Content-Type" "application/json"}
             :body (str "{\"url\":\"" url "\"}")}))))))

(defn redirect-upload [req]
  (let* [id    (unique-id)
         token (get (req :params) "token")
         data  (get (req :params) "redirect")]
    (when-let [username (validate-token-return-username token)]
      (swap! db/shortened conj
             {:id id :type "redirect" :data data :username username})
      (db/save-shortened!)
            (build-short-url req id))))

(defn return-shortened [id]
  (let [entry (first (filter #(= id (:id %)) @db/shortened))]
    (if (= (:type entry) "redirect")
      {:status  302
       :headers {"Location" (:data entry)}
       :body    ""}
      {:status  200
       :headers {"Content-Type" (:type entry)}
       :body    (io/file (str file-storage-location (:id entry)))})))