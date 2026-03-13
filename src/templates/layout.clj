(ns templates.layout
  (:require [hiccup.element :as elem]
            [hiccup.form :as form]
            [clojure.string :as str]
            [templates.tracking :refer [tracking-head]]
            [hiccup.page :refer [include-css include-js html5]]
            [backend.blog :as blog]
            [backend.users :as users]))

(defn navbar [options]
  [:div.navbarcontain
   [:div.navbar {:style "font-family: 'Jacques Francois';"}
    (when (users/admin-logged-in? (:request options))
      [:a.right {:href "/admin"} "Admin"])
    [:a.right {:href "/blog"} "Blog"]
    [:a.right {:href "/bio"} "Bio"]
    [:div.navbarname [:a {:href "/"}
                      "Solomon Bloch"]]]])

(def footer
  [:div.footer
   [:div.footercontents
    [:div "©2020 Sol Bloch"]
    [:div
     (elem/link-to "https://www.linkedin.com/in/solomon-bloch-151309167/"
                   "LinkedIn") " | "
     (elem/link-to "https://www.instagram.com/noogietheguru" "Instagram")  " | "
     (elem/link-to "mailto:solomonbloch@gmail.com" "Email")]]])

(def head
  [:head tracking-head
   [:link {:rel  "alternate"
           :type "application/rss+xml"
           :title "Sol Explores The World"
           :href "/feed.xml"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
   [:title "solB"]])

(defmacro page-template
  [options & forms]
  (if-not (map? options)
    `(page-template {} ~options ~@forms)
    `(html5 (include-css "/styles/style.css")
            (if (contains? ~options :js)
              (if (string? (~options :js))
                (include-js (~options :js))
                (map include-js (~options :js))))
            (if (contains? ~options :title)
              [:title (~options :title)])
            [:html ~head
             [:body
              [:div.main
               [:header (navbar ~options)]
               ~@forms]
              footer]])))

(defn- format-date [date-str]
  (subs date-str 0 10))

(def ^:private void-tags
  #{"area" "base" "br" "col" "embed" "hr" "img" "input"
    "link" "meta" "param" "source" "track" "wbr"})

(defn- strip-leading-empty-html [content]
  (loop [trimmed (str/triml content)]
    (let [next (-> trimmed
                   (str/replace-first #"(?is)\A(?:<br\s*/?>|&nbsp;|\s)+" "")
                   (str/replace-first
                    #"(?is)\A<(?:div|p)[^>]*>\s*(?:<br\s*/?>|&nbsp;|\s)*</(?:div|p)>\s*"
                    ""))]
      (if (= next trimmed)
        trimmed
        (recur (str/triml next))))))

(defn- truncate-html [content limit]
  (loop [tokens (re-seq #"(?is)<[^>]+>|[^<]+" content)
         remaining limit
         stack []
         out []]
    (if (or (empty? tokens) (<= remaining 0))
      (let [truncated? (seq tokens)]
        (str (apply str out)
             (when truncated? "…")
             (apply str (map #(str "</" % ">") (reverse stack)))))
      (let [token (first tokens)]
        (if (str/starts-with? token "<")
          (let [[_ closing? tag-name] (re-find #"(?is)^<(/)?\s*([a-z0-9]+)" token)
                tag-name (some-> tag-name str/lower-case)
                self-closing? (boolean (re-find #"(?is)/\s*>$" token))
                next-stack (cond
                             (or (nil? tag-name)
                                 self-closing?
                                 (void-tags tag-name)) stack
                             closing? (if (= tag-name (peek stack))
                                        (pop stack)
                                        stack)
                             :else (conj stack tag-name))]
            (recur (rest tokens) remaining next-stack (conj out token)))
          (let [take-count (min remaining (count token))
                text-piece (subs token 0 take-count)]
            (recur (rest tokens)
                   (- remaining take-count)
                   stack
                   (conj out text-piece))))))))

(defn- post-snippet [content]
  (if (str/includes? content "convo-transcript")
    (let [messages (re-seq #"(?s)<div class=\"convo-message[^\"]*\">.*?</div>.*?</div>.*?</div>" content)]
      (StringBuilder.
        (if (seq messages)
          (str "<div class=\"convo-transcript\">"
               (str/join "" (take 2 messages))
               "</div>")
          "")))
    (let [snippet (-> content
                      strip-leading-empty-html
                      (truncate-html 350))]
      (StringBuilder. snippet))))

(defn homepage
  ([] (homepage nil))
  ([request]
   (page-template
   {:title "solB"
    :request request}
   [:div.blogonly
    (for [i (reverse (blog/live-posts))]
      [:div.entry
       [:div.entry-title-row
        [:a.entry-title {:href (str "/blog/" (:link i))}
         (:title i)]
        [:span.entry-readtime
         "~" (int (Math/ceil (/ (count (str/split (:content i) #"\s+")) 265))) " min"]]
       [:div.snippet.snippet-link
        {:role "link"
         :tabindex "0"
         :data-href (str "/blog/" (:link i))
         :onclick "window.location=this.dataset.href"
         :onkeydown "if(event.key==='Enter'||event.key===' '){window.location=this.dataset.href;event.preventDefault();}"}
        (post-snippet (:content i))]
       [:div.entry-footer
        (format-date (:date i)) " · "
        (for [tag (str/split (:tags i) #" ")]
          (elem/link-to {:class "tag-link"} (str "/blog/tags/" tag)
                        (str ":" tag)))]])])))

(defn bio
  ([] (bio nil))
  ([request]
   (page-template
   {:title "Bio"
    :request request}
   [:div.blog
    [:div.content
     "I'm Sol, ex-Google-interviewee. I studied at Syracuse University, receiving a major in applied mathematics, and minors in both computer science, and physics. And had a blast! If you'd like to see, here's my "
     (elem/link-to  "https://docs.google.com/document/d/1Q33ErfDa9UBIAdvirshkTVl9We6zalZAxsgp3X_tH1g/edit" "resume") "."
     ]])))

(defn su-cal
  ([] (su-cal nil))
  ([request]
   (page-template
   {:js "/scripts/su-cal.js"
    :title "su-cal"
    :request request}
   [:div.blog
    [:h1 "ical your schedule"]
    [:div.content
     [:ul
      [:li "Log into MySlice, click on \"View Class Schedule\""]
      [:li "Click \"View Printer Friendly Version\""]
      [:li "Copy the entire page (Ctrl+A then Ctrl+C)"]
      [:li "Paste into the text field below."]
      [:li "Click the button, save the file as a .ics file (something with .ics after it's name, e.g. "
       [:code.tidbit "cal.ics"] "."]
      "Then you can import this file into any calendar appliation you'd like."
      [:br]
      "NOTE: I " [:strong "STRONGLY"]
      " recommend making a new calendar on Google Calendar before importing the ICS. Just in case something breaks."]]
    (form/text-area {:id "cal-raw"} "cal")
    [:button {:onclick "window.open('/su-cal-gen?cal=' + encodeURI(document.getElementById('cal-raw').value.replace(/&/g,'')))"
              :style   "display: block; margin: auto;"}
      "get ics"]])))
