(ns templates.blog
  (:require [hiccup.element :as elem]
            [hiccup.form :as form]
            [hiccup.page :refer [html5]]
            [hiccup.util :refer [url-encode]]
            [ring.util.anti-forgery :as anti]
            [clojure.string :as str]
            [backend.blog :as blog]
            [backend.users :as users]
            [templates.layout :as layout]
            [ring.util.response :as resp]))

(defn- raw [s] (StringBuilder. s))

(defn- format-date [date-str]
  (subs date-str 0 10))

(defn- post-action-link [href label]
  [:a.toolbar-reload {:href href}
   label])

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

(defn- convo-snippet [content]
  (let [transcript-start (str/index-of content "<div class=\"convo-transcript\">")
        intro-html (when transcript-start
                     (subs content 0 transcript-start))
        intro-text (some-> intro-html
                           strip-leading-empty-html
                           (str/replace #"<[^>]+>" "")
                           (str/replace #"\s+" " ")
                           str/trim)
        intro-preview (when (seq intro-text)
                        (str "<p>"
                             (if (> (count intro-text) 220)
                               (str (subs intro-text 0 220) "…")
                               intro-text)
                             "</p>"))
        transcript-html (if transcript-start
                          (subs content transcript-start)
                          content)
        messages (re-seq #"(?s)<div class=\"convo-message[^\"]*\">.*?</div>.*?</div>.*?</div>" transcript-html)]
    (str (or intro-preview "")
         (when (seq messages)
           (str "<div class=\"convo-transcript\">"
                (str/join "" (take 2 messages))
                "</div>")))))

(defn- post-snippet [content]
  (if (str/includes? content "convo-transcript")
    (StringBuilder. (or (convo-snippet content) ""))
    (let [paras (re-seq #"(?s)<p>(.*?)</p>" content)
          first-two (take 2 paras)]
      (StringBuilder.
       (if (seq first-two)
         (str/join ""
           (map (fn [[_ text]]
                  (if (> (count text) 350)
                    (str "<p>" (subs text 0 350) "…</p>")
                    (str "<p>" text "</p>")))
                first-two))
         (let [text (-> content
                        (str/replace #"<[^>]+>" "")
                        (str/replace #"\s+" " ")
                        str/trim)]
           (if (> (count text) 280)
             (str (subs text 0 280) "…")
             text)))))))

(defn blog-post-html
  ([post-list] (blog-post-html nil post-list))
  ([request post-list]
   (layout/page-template
   {:title "solB blog"
    :request request}
   [:div.blogonly
    (for [i (reverse post-list)]
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

(defn blog-homepage
  ([] (blog-homepage nil))
  ([request]
   (blog-post-html request (blog/live-posts))))

(defn tag-page
  ([tag] (tag-page nil tag))
  ([request tag]
   (blog-post-html request (blog/like-tag tag))))

(defn admin [request]
  (layout/page-template
   {:js "/scripts/admin.js"
    :title "solb admin"
    :request request}
   [:div.adminstuff
    [:a {:href "/newpost"} "new post"]
    [:a {:href "/newconvo"} "new convo"]
    [:a#token {:onclick
               "getRequest(\"/showtoken\",
                  (txt)=>{ document.getElementById(\"token\").innerHTML = txt})"}
     "show token"]
    [:a {:onclick
         "getRequest(\"/generatetoken\",
            (txt)=>{ document.getElementById(\"token\").innerHTML = txt});"}
     "get new token"]]
   [:div.blogonly
    (for [i (reverse (blog/all-posts))]
      [:div.entry
       [:div.admin-entry-header
        [:a.entry-title {:href (str "/blog/" (:link i))}
         (:title i)]
        [:div.admin-entry-meta
         [:div.tags.admin-entry-actions
         (elem/link-to (str "/blog/" (:link i) "/edit") "::edit::")
         [:form {:enctype "multipart/form-data"
                 :id      (str "enlivenform" (:id i))
                 :action  "/enlive"
                 :method  "post"}
          (anti/anti-forgery-field)
          (form/hidden-field "id" (:id i))
          [:a {:href    "#"
               :onclick (str "document.getElementById('enlivenform"
                             (:id i) "').submit();")}
           (if (:status i) "::endraften::" "::enliven::")]]
         [:form {:enctype "multipart/form-data"
                 :id      (str "deleteform" (:id i))
                 :action  "/delete"
                 :method  "post"}
          (anti/anti-forgery-field)
          (form/hidden-field "id" (:id i))
          [:a {:href    "#"
               :style   "color: #c33;"
               :onclick (str "if(confirm('delete \""
                             (:title i) "\"?')) document.getElementById('deleteform"
                             (:id i) "').submit();")}
           "::delete::"]]
         (for [tag (str/split (:tags i) #" ")]
           (elem/link-to (str "/blog/tags/" tag)
                         (str ":" tag)))]
         [:div.date (format-date (:date i))]]]])]))


(defn htmlitize
  ([entry-title] (htmlitize nil entry-title))
  ([request entry-title]
  (let [entry (blog/get-post-by-link (url-encode entry-title))]
    (if (:status entry)
      (layout/page-template
       {:title (:title entry)
        :request request}
       [:div.blog
        [:h2 (:title entry)]
        (when (users/admin-logged-in? request)
          [:div.editor-toolbar.post-toolbar
           (post-action-link (str "/blog/" (:link entry) "/edit") "Edit")])
        [:div.datetagsflex
         [:div.tags
          (for [tag (str/split (:tags entry) #" ")]
            (elem/link-to (str "/blog/tags/" tag)
                          (str ":" tag)))]
         [:div.date (format-date (:date entry))]]
        [:div.content (raw (:content entry))]
        [:div.rss (elem/link-to "https://solb.io/feed.xml" "RSS FEED")]])
      (html5 "You just tried to look at a post that isn't live! How!!")))))

(defn htmlitize-edit!
  ([entry-title] (htmlitize-edit! nil entry-title))
  ([request entry-title]
  (let [entry (blog/get-post-by-link (url-encode entry-title))]
    (layout/page-template
     {:request request}
     [:script {:src "https://cdn.jsdelivr.net/npm/quill@1.3.7/dist/quill.min.js"}]
     [:link {:rel "stylesheet" :href "https://cdn.jsdelivr.net/npm/quill@1.3.7/dist/quill.snow.css"}]
     [:div.editor-toolbar
      [:span#save-status.save-status "saved"]
      [:button.toolbar-save {:onclick "event.preventDefault(); manualSave()"} "Save"]
      [:button.toolbar-reload {:onclick "event.preventDefault(); saveAndReload()"} "Save & Reload"]
      [:button.toolbar-reload {:onclick "event.preventDefault(); triggerImageUpload()"} "Upload Image"]
      [:button.toolbar-reload {:onclick "event.preventDefault(); resizeSelectedImage()"} "Resize Image"]
      (when (:status entry)
        (post-action-link (str "/blog/" (:link entry)) "View Live Post"))
      [:form {:enctype "multipart/form-data"
              :action  "/enlive"
              :method  "post"
              :style   "display: inline; margin-left: 8px;"}
       (anti/anti-forgery-field)
       (form/hidden-field "id" (:id entry))
       [:button.toolbar-reload {:type "submit"}
        (if (:status entry) "Endraften" "Enliven")]]]
     [:div.blog.edit-mode
      [:h2#post-title {:contenteditable "true" :spellcheck "false"} (:title entry)]
      [:div.datetagsflex
       [:div#post-tags.tags {:contenteditable "true" :spellcheck "false"}
        (:tags entry)]
       [:div.date (format-date (:date entry))]]
      [:div#post-forward.forward {:contenteditable "true"}
       (:forward entry)]
        [:div#editor]
        [:div#convo-editor-shell {:style "display:none"}
         [:div.editor-section
          [:div.editor-section-label "Intro"]
          [:div#editor-intro]]
         [:div.editor-section
          [:div.editor-section-label "Transcript"]
          [:div#editor-transcript.raw-convo-editor {:contenteditable "true"
                        :spellcheck "false"}]]
         [:div.editor-section
          [:div.editor-section-label "Outro"]
          [:div#editor-outro]]]
      [:div#initial-content {:style "display:none"} (raw (:content entry))]
      [:form#save-form {:enctype "multipart/form-data"
                        :action  "/editor"
                        :method  "post"}
       (anti/anti-forgery-field)
       (form/hidden-field "content")
       (form/hidden-field "tags")
       (form/hidden-field "title")
       (form/hidden-field "forward")
       (form/hidden-field "id" (:id entry))]]
    [:script {:src "/scripts/contenteditable.js"}]))))

(defn new-post []
  (blog/make-draft!
   :title   "new title"
   :forward "forward"
   :tags    "tags"
   :content "write me!")
  (resp/redirect "/blog/new-title/edit"))

(defn new-convo
  ([] (new-convo nil))
  ([request]
  (layout/page-template
   {:title "new convo"
    :request request}
   [:div.blog
    [:h2 "new convo import"]
    [:div.content
     "Paste your chat export below and a draft post will be generated. You can edit/fix anything on the next screen."]
    [:div.content
      [:p "Use this prompt with your LLM to export a full markdown transcript."]
      [:button {:type "button"
          :onclick "copyConvoPrompt()"
          :style "margin-bottom: 8px;"}
       "Copy prompt"]
      [:span#copy-prompt-status {:style "margin-left: 8px; color: rgba(0,0,0,.55); font-size: 12px;"}]
      [:details
       [:summary {:style "cursor: pointer; margin: 6px 0;"} "Show full prompt"]
       [:textarea#convo-prompt {:readonly true
                 :style "width: 100%; min-height: 280px; margin-top: 8px;"}
        "You are a **conversation transcript generator**.

    Your task is to convert the **visible conversation in this chat** that occurred **before this prompt** into a **clean Markdown transcript**.

    You must **only include the visible user and assistant messages from this chat**.

    Do **NOT** include or attempt to reveal:

    * system messages
    * developer messages
    * hidden instructions
    * safety policies
    * internal metadata

    If such messages exist, **ignore them completely**.

    This prompt **and anything after it must NOT appear in the transcript**.

    ---

    # Rules

    ## 1. Output format

    * Output **Markdown only**
    * No explanations or commentary
    * The output must be directly savable as a `.md` file

    ---

    ## 2. Scope of the transcript

    Include:

    * All **visible messages from Sol**
    * All **visible responses from the assistant**

    Exclude:

    * This prompt
    * Any instructions requesting the transcript
    * Any system/developer messages
    * Any hidden internal messages
    * Any messages after this prompt

    ---

    ## 3. Speaker labels

    Use these exact labels:

    `## Sol`
    for the user.

    `## <MODEL_NAME>`
    for the assistant.

    Replace `<MODEL_NAME>` with your **actual model name**, for example:

    * `## GPT-5.3`
    * `## Claude 3.7 Sonnet`
    * `## Gemini 2.5 Pro`

    ---

    ## 4. Speaker change rule

    Only print a speaker heading **when the speaker changes**.

    A heading must appear **once per continuous block of messages from that speaker**, not once per message.

    Correct:

    ```
    ## Sol
    First message.

    Second message.

    ---

    ## GPT-5.3
    Reply.

    Another reply.
    ```

    Incorrect (do NOT do this):

    ```
    ## Sol
    First message.

    ## Sol
    Second message.

    ## GPT-5.3
    Reply.

    ## GPT-5.3
    Another reply.
    ```

    Never output the **same speaker heading twice in a row**.

    ---

    ## 5. Message separator

    Place this between messages:

    ```
    ---
    ```

    ---

    ## 6. Formatting preservation

    Preserve the **exact original content** of messages.

    Do NOT:

    * summarize
    * rewrite
    * remove formatting

    All formatting must render correctly, including:

    * headings
    * bold / italics
    * bullet lists
    * numbered lists
    * block quotes
    * inline code
    * code blocks
    * tables
    * HTML
    * Markdown links

    ---

    ## 7. Links

    All links must remain **clickable and unchanged**.

    Allowed forms:

    ```
    https://example.com
    ```

    or

    ```
    [Example](https://example.com)
    ```

    Do not alter URLs.

    ---

    ## 8. Code blocks

    If a message contains code, preserve it exactly using triple backticks.

    Example:

    ```python
    print(\"hello\")
    ```

    Preserve language identifiers.

    ---

    ## 9. Images

    If an image URL exists, render it as:

    ```
    ![Image](URL)
    ```

    If no URL exists:

    ```
    [Image]
    ```

    ---

    ## 10. Ordering

    Preserve the **exact chronological order** of visible messages.

    Start with the **first visible message in this chat** and continue until the **last visible message before this prompt**.

    ---

    ## 11. Completeness

    Do **not truncate** the transcript.

    Generate the entire conversation even if long.

    ---

    Begin the Markdown transcript now."]]]
    [:form {:enctype "multipart/form-data"
            :action  "/newconvo"
            :method  "post"}
     (anti/anti-forgery-field)
     [:div.content
      [:p "Title (optional)"]
      (form/text-field {:style "width: 100%;"} "title")
      [:p "Model (e.g. gpt / claude / gemini / deepseek)"]
      (form/text-field {:style "width: 100%;"} "model")
      [:p "Topic tag (optional, one short word)"]
      (form/text-field {:style "width: 100%;"} "topic")
      [:p "Transcript export"]
      (form/text-area {:name "raw_content"
                       :id "raw_content"
                       :style "width: 100%; min-height: 380px;"} "")]
     [:div {:style "margin-top: 12px;"}
        [:input {:type "submit" :value "create convo draft"}]]
      [:script "function copyConvoPrompt() {\n  var promptEl = document.getElementById('convo-prompt');\n  var statusEl = document.getElementById('copy-prompt-status');\n  if (!promptEl) return;\n  var text = promptEl.value;\n  if (navigator.clipboard && navigator.clipboard.writeText) {\n    navigator.clipboard.writeText(text).then(function() {\n      if (statusEl) statusEl.textContent = 'Copied';\n    }).catch(function() {\n      promptEl.select();\n      document.execCommand('copy');\n      if (statusEl) statusEl.textContent = 'Copied';\n    });\n  } else {\n    promptEl.select();\n    document.execCommand('copy');\n    if (statusEl) statusEl.textContent = 'Copied';\n  }\n}"]]])))
