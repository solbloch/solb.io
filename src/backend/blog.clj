(ns backend.blog
  (:require [clj-rss.core :as rss]
            [hiccup.util :refer [url-encode]]
            [backend.db :as db]
            [ring.util.response :as resp]
            [clojure.string :as str]))

(defn- now-str []
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss") (java.util.Date.)))

(defn content-format [content]
  (-> content
      (str/replace #"(?is)```(.*)```"
                   (str "<pre><code>" "$1" "</pre></code>"))
      (str/replace #"(?is)`(\S*)`"
                   (str "<span class=\"tidbit\">" "$1" "</span>"))))

(defn- escape-html [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(defn- slugify [title]
  (-> title
      str/lower-case
      (str/replace #"[^a-z0-9\s-]" "")
      (str/replace #"\s+" "-")
      (str/replace #"-+" "-")))

(defn- html-content? [content]
  (boolean (re-find #"(?is)<\s*(p|blockquote|pre|code|ul|ol|li|h1|h2|h3|div|span|a)\b"
                    (or content ""))))

(defn- render-inline-markdown [text]
  (-> (escape-html text)
      (str/replace #"\[([^\]]+)\]\((https?://[^)\s]+)\)"
                   (fn [[_ label href]]
                     (str "<a href=\""
                          href
                          "\" target=\"_blank\" rel=\"noopener noreferrer\">"
                          label
                          "</a>")))
      (str/replace #"`([^`]+)`" "<code>$1</code>")
      (str/replace #"\*\*([^*]+)\*\*" "<strong>$1</strong>")
      (str/replace #"\*([^*]+)\*" "<em>$1</em>")))

(defn- render-rich-markdown [content]
  (let [flush-paragraph
        (fn [{:keys [out paragraph] :as state}]
          (if (seq paragraph)
            (assoc state
                   :out (conj out
                              (str "<p>"
                                   (->> paragraph
                                        (map render-inline-markdown)
                                        (str/join "<br/>"))
                                   "</p>"))
                   :paragraph [])
            state))
        close-ul
        (fn [{:keys [out in-ul] :as state}]
          (if in-ul
            (-> state
                (assoc :out (conj out "</ul>"))
                (assoc :in-ul false))
            state))
        close-ol
        (fn [{:keys [out in-ol] :as state}]
          (if in-ol
            (-> state
                (assoc :out (conj out "</ol>"))
                (assoc :in-ol false))
            state))
        close-lists (fn [state] (-> state close-ul close-ol))
        flush-code
        (fn [{:keys [out in-code code-lines] :as state}]
          (if in-code
            (-> state
                (assoc :out (conj out
                                  (str "<pre><code>"
                                       (->> code-lines
                                            (map escape-html)
                                            (str/join "\n"))
                                       "</code></pre>")))
                (assoc :in-code false :code-lines []))
            state))
        finish (fn [state]
                 (-> state
                     flush-paragraph
                     close-lists
                     flush-code
                     :out))
        result (reduce (fn [{:keys [in-code] :as state} line]
                         (let [trimmed (str/trim line)
                               header-match (re-find #"^(#{1,6})\s+(.+)$" trimmed)
                               ul-match (re-find #"^[-*]\s+(.+)$" trimmed)
                               ol-match (re-find #"^\d+[\.)]\s+(.+)$" trimmed)
                               quote-match (re-find #"^>\s?(.*)$" trimmed)
                               fence? (re-find #"^```" trimmed)]
                           (cond
                             fence?
                             (if in-code
                               (flush-code state)
                               (-> state flush-paragraph close-lists (assoc :in-code true :code-lines [])))

                             in-code
                             (update state :code-lines conj line)

                             (empty? trimmed)
                             (-> state flush-paragraph close-lists)

                             header-match
                             (let [level (count (second header-match))
                                   text (nth header-match 2)]
                               (-> state
                                   flush-paragraph
                                   close-lists
                                   (update :out conj (str "<h" level ">"
                                                          (render-inline-markdown text)
                                                          "</h" level ">"))))

                             quote-match
                             (let [text (second quote-match)]
                               (-> state
                                   flush-paragraph
                                   close-lists
                                   (update :out conj (str "<blockquote><p>"
                                                          (render-inline-markdown text)
                                                          "</p></blockquote>"))))

                             ul-match
                             (let [item (second ul-match)]
                               (-> state
                                   flush-paragraph
                                   close-ol
                                   ((fn [{:keys [in-ul] :as s}]
                                      (if in-ul
                                        s
                                        (-> s
                                            (update :out conj "<ul>")
                                            (assoc :in-ul true)))))
                                   (update :out conj (str "<li>" (render-inline-markdown item) "</li>"))))

                             ol-match
                             (let [item (second ol-match)]
                               (-> state
                                   flush-paragraph
                                   close-ul
                                   ((fn [{:keys [in-ol] :as s}]
                                      (if in-ol
                                        s
                                        (-> s
                                            (update :out conj "<ol>")
                                            (assoc :in-ol true)))))
                                   (update :out conj (str "<li>" (render-inline-markdown item) "</li>"))))

                             :else
                             (-> state
                                 close-lists
                                 (update :paragraph conj trimmed)))))
                       {:out []
                        :paragraph []
                        :in-ul false
                        :in-ol false
                        :in-code false
                        :code-lines []}
                       (str/split-lines (or content "")))]
    (->> (finish result)
         (remove str/blank?)
         (str/join "\n"))))

(defn- format-convo-plain-text [raw]
  (let [human-labels #{"human" "me" "sol" "user"}
        model-labels #{"model" "assistant" "gpt" "claude" "gemini" "deepseek"}
        speaker-kind (fn [label]
                       (let [normalized (str/lower-case label)]
                         (cond
                           (contains? human-labels normalized) :human
                           (contains? model-labels normalized) :model
                           :else nil)))
        render-block (fn [{:keys [kind label lines]}]
                       (let [content (->> lines
                                          (str/join "\n")
                                          str/trim)
                             content-html (render-rich-markdown content)]
                         (when (seq content)
                           (str "<div class=\"convo-message "
                                (if (= kind :human) "convo-human" "convo-model")
                                "\">"
                                "<div class=\"convo-speaker\">"
                                (escape-html (if (= kind :human) "Sol" label))
                                "</div>"
                                "<div class=\"convo-bubble\">"
                                content-html
                                "</div>"
                                "</div>"))))
        resolve-speaker
        (fn [label]
          (let [trimmed (str/trim (or label ""))
                kind (speaker-kind trimmed)]
            (cond
              (= kind :human) {:kind :human :label "Sol"}
              (= kind :model) {:kind :model :label (if (str/blank? trimmed) "Model" trimmed)}
              (seq trimmed)   {:kind :model :label trimmed}
              :else nil)))
        lines (vec (str/split-lines (or raw "")))
        next-non-empty-line (fn [idx]
                              (some (fn [line]
                                      (let [trimmed (str/trim line)]
                                        (when (seq trimmed) trimmed)))
                                    (subvec lines (min (inc idx) (count lines)) (count lines))))
        blocks (reduce (fn [{:keys [current blocks expecting-speaker]} [idx line]]
                         (let [trimmed (str/trim line)
                               separator? (= trimmed "---")
                               next-non-empty (when separator? (next-non-empty-line idx))
                               next-header? (boolean (and next-non-empty
                                                          (re-find #"^##\s+(.+)$" next-non-empty)))
                               next-colon-match (and next-non-empty
                                                     (re-find #"(?is)^([a-z0-9 ._-]+)\s*:\s*(.*)$"
                                                              next-non-empty))
                               next-colon-label (some-> next-colon-match second)
                               separator-boundary? (and separator?
                                                       (or next-header?
                                                           (and next-colon-label
                                                                (speaker-kind next-colon-label))))
                 header-match (when expecting-speaker
                        (re-find #"^##\s+(.+)$" trimmed))
                 header-label (when header-match (second header-match))
                               colon-match  (re-find #"(?is)^([a-z0-9 ._-]+)\s*:\s*(.*)$" trimmed)
                               colon-label  (some-> colon-match second)
                               colon-text   (some-> colon-match (nth 2))
                               header-speaker (and header-label (resolve-speaker header-label))
                               colon-speaker  (and colon-label
                                                   (when-let [resolved (resolve-speaker colon-label)]
                                                     (when (speaker-kind colon-label)
                                                       resolved)))]
                           (cond
                             separator-boundary?
                             {:current nil
                              :blocks (cond-> blocks current (conj current))
                              :expecting-speaker true}

                             header-speaker
                             {:current {:kind (:kind header-speaker)
                                        :label (:label header-speaker)
                                        :lines []}
                              :blocks (cond-> blocks current (conj current))
                              :expecting-speaker false}

                             colon-speaker
                             {:current {:kind (:kind colon-speaker)
                                        :label (:label colon-speaker)
                                        :lines (cond-> [] (seq (str/trim colon-text)) (conj colon-text))}
                              :blocks (cond-> blocks current (conj current))
                              :expecting-speaker false}

                             (and current (seq trimmed))
                             {:current (update current :lines conj line)
                              :blocks  blocks
                              :expecting-speaker false}

                             (and current (empty? trimmed))
                             {:current (update current :lines conj "")
                              :blocks blocks
                              :expecting-speaker false}

                             (seq trimmed)
                             {:current {:kind :human :label "Sol" :lines [line]}
                              :blocks blocks
                              :expecting-speaker false}

                             :else
                             {:current current :blocks blocks :expecting-speaker expecting-speaker})))
                       {:current nil :blocks [] :expecting-speaker true}
                       (map-indexed vector lines))
        all-blocks (cond-> (:blocks blocks)
                     (:current blocks) (conj (:current blocks)))]
        (let [messages (->> all-blocks
                (map render-block)
                (remove str/blank?))]
       (if (seq messages)
         (str "<div class=\"convo-transcript\">"
           (str/join "\n" messages)
           "</div>")
         ""))))

(defn- normalize-convo-content [raw]
  (let [trimmed (str/trim (or raw ""))]
    (if (html-content? trimmed)
      trimmed
      (format-convo-plain-text trimmed))))

(defn- next-id []
  (if (empty? @db/posts)
    1
    (inc (apply max (map :id @db/posts)))))

(defn all-posts []
  (sort-by :date @db/posts))

(defn live-posts []
  (sort-by :date (filter :status @db/posts)))

(defn drafts-posts []
  (sort-by :date (remove :status @db/posts)))

(defn get-post-by-link [link]
  (first (filter #(= link (:link %)) @db/posts)))

(defn like-tag [tag]
  (sort-by :date (filter #(str/includes? (:tags %) tag) @db/posts)))

(defn edit-post! [& {:keys [id title content tags forward]}]
  (let [link (url-encode (str/lower-case (str/replace title " " "-")))]
    (swap! db/posts
           (fn [posts]
             (mapv #(if (= (:id %) (Integer/parseInt (str id)))
                      (assoc % :title title :content content
                               :tags tags :forward forward :link link)
                      %)
                   posts)))
    (db/save-posts!)
    link))

(defn edit! [req]
  (let* [params   (req :params)
         content  (:content params)
         title    (:title params)
         forward  (:forward params)
         tags     (:tags params)
         id       (:id params)]
    (as-> (edit-post! :content content :title title :forward forward
                      :tags tags :id (read-string id)) $
      (resp/redirect (str "/blog/" $ "/edit")))))

(defn make-draft! [& {:keys [title tags content forward]
                      :or {title   "no title"
                           tags    "no tags"
                           content "empty"
                           forward "no forward"}}]
  (let [id                (next-id)
    base-link         (slugify title)
    link              (if (str/blank? base-link)
              (str "draft-" id)
              base-link)
        date              (now-str)
        content-formatted (if (html-content? content)
                            content
                            (content-format content))]
    (swap! db/posts conj
           {:id id :title title :status false :link link
            :date date :tags tags :forward forward :content content-formatted})
  (db/save-posts!)
  link))

(defn import-convo! [req]
  (let [params       (:params req)
    model        (str/trim (or (:model params) ""))
    topic        (str/trim (or (:topic params) ""))
    raw-content  (:raw_content params)
    title        (str/trim (or (:title params) ""))
    title        (if (seq title)
             title
             (str "convo"
              (when (seq model) (str " - " model))
              (when (seq topic) (str " - " topic))))
    convo-html   (normalize-convo-content raw-content)
    forward      (str "Conversation transcript"
              (when (seq model) (str " with " model))
              (when (seq topic) (str " about " topic))
              ".")
    tags         (str/trim (str "convo "
                  (str/lower-case model)
                  " "
                  (str/lower-case topic)))
    link         (make-draft! :title title
                  :tags tags
                  :forward (if (seq forward) forward "new convo draft")
                  :content convo-html)]
  (resp/redirect (str "/blog/" link "/edit"))))

(defn generate-rss []
  (let [sorted (sort-by :date (filter :status @db/posts))]
    (spit "resources/public/feed.xml"
          (rss/channel-xml
           {:title       "Sol Explores The World"
            :link        "https://solb.io"
            :description "Mostly posts about programming stuff, maybe something else thrown in once in a while."}
           (for [post sorted]
             {:title       (:title post)
              :pubDate     (.parse (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss")
                                   (subs (:date post) 0 19))
              :description (:forward post)
              :link        (str "https://solb.io/blog/" (:link post))
              :category    [(for [tag (str/split (:tags post) #" ")]
                              [{:domain (str "https://solb.io/blog/tags/" tag)} tag])]})))))

(defn autosave! [req]
  (let [params   (:params req)
        content  (:content params)
        title    (:title params)
        forward  (:forward params)
        tags     (:tags params)
        id       (read-string (:id params))]
    (swap! db/posts
           (fn [posts]
             (mapv #(if (= (:id %) id)
                      (assoc % :title title :content content :tags tags :forward forward)
                      %)
                   posts)))
    (db/save-posts!)
    {:status 200 :headers {"Content-Type" "application/json"} :body "{\"ok\":true}"}))

(defn delete! [req]
  (let [id (Integer/parseInt (:id (:params req)))]
    (swap! db/posts (fn [posts] (vec (remove #(= (:id %) id) posts))))
    (db/save-posts!))
  (resp/redirect "/admin"))

(defn enliven [req]
  (let [id (Integer/parseInt (:id (:params req)))]
    (swap! db/posts
           (fn [posts]
             (mapv #(if (= (:id %) id)
                      (assoc % :status (not (:status %)) :date (now-str))
                      %)
                   posts)))
    (db/save-posts!))
  (generate-rss)
  (resp/redirect "/admin"))