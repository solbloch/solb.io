(ns backend.users
  (:require [backend.utils :as utils]
            [backend.db :as db]
            [buddy.core.keys :as keys]
            [buddy.sign.jwt :as jwt]
            [buddy.hashers :as hashers]
            [ring.util.response :as resp]))

(def privkey (keys/private-key "src/backend/keys/ecprivkey.pem"))
(def pubkey  (keys/public-key  "src/backend/keys/ecpubkey.pem"))

(defn- find-user [username]
  (first (filter #(= username (:username %)) @db/users)))

(defn- token-username [request]
  (try
    (-> (get-in request [:cookies "token" :value])
        (jwt/unsign pubkey {:alg :es256})
        :user)
    (catch Exception _ nil)))

(defn login-user [request]
  (let [params   (:params request)
        username (:username params)
        password (:password params)
        user     (find-user username)]
    (if (and user (hashers/check password (:password user)))
      (let [token (jwt/sign {:user (keyword username)} privkey {:alg :es256})]
        (-> (resp/redirect "/admin")
            (assoc :cookies {"token" {:value token :max-age 259200}})))
      "no")))

(defn admin-logged-in? [request]
  (= "sol" (str (token-username request))))

(defn sol? [request next]
  (if (admin-logged-in? request)
    next
    (resp/redirect "/login")))

(defn- unique-token []
  (let [new-token (utils/generate-token)]
    (if (some #(= new-token (:token %)) @db/users)
      (unique-token)
      new-token)))

(defn show-token [request]
  (when-let [username (token-username request)]
    (:token (find-user (str username)))))

(defn add-token [request]
  (when-let [username (token-username request)]
    (let [uname     (str username)
          new-token (unique-token)]
      (swap! db/users
             (fn [users]
               (mapv #(if (= (:username %) uname)
                        (assoc % :token new-token)
                        %)
                     users)))
      (db/save-users!)
      new-token)))
