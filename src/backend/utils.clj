(ns backend.utils
  (:require [clojure.java.io :as io]
            [backend.db :as db]))

(defonce characters "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")

(def file-storage-location "/home/sol/files/")

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
      (str "https://solb.io/" id))))

(defn redirect-upload [req]
  (let* [id    (unique-id)
         token (get (req :params) "token")
         data  (get (req :params) "redirect")]
    (when-let [username (validate-token-return-username token)]
      (swap! db/shortened conj
             {:id id :type "redirect" :data data :username username})
      (db/save-shortened!)
      (str "https://solb.io/" id))))

(defn return-shortened [id]
  (let [entry (first (filter #(= id (:id %)) @db/shortened))]
    (if (= (:type entry) "redirect")
      {:status  302
       :headers {"Location" (:data entry)}
       :body    ""}
      {:status  200
       :headers {"Content-Type" (:type entry)}
       :body    (io/file (str file-storage-location (:id entry)))})))