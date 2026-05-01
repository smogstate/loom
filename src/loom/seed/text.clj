(ns loom.seed.text
  "Text manipulation tools."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [loom.envelope :refer [with-provenance]]]))

(defn split-lines
  "Split text into a vector of non-empty lines."
  {:doc "Split text into non-empty lines. Returns vector of strings."
   :tags ["text" "split" "lines"]}
  [ctx text]
  (with-provenance "loom.seed.text/split-lines" 1
    (filterv (complement str/blank?) (str/split-lines text))))

(defn count-tokens
  "Rough token estimate: word count × 1.3."
  {:doc "Estimate token count for a string (word-count × 1.3)."
   :tags ["text" "tokens" "count"]}
  [ctx text]
  (with-provenance "loom.seed.text/count-tokens" 1
    (int (* 1.3 (count (str/split text #"\s+"))))))

(defn extract-json
  "Extract the first JSON object or array from a string."
  {:doc "Extract first JSON value from a string containing embedded JSON."
   :tags ["text" "json" "extract"]}
  [ctx text]
  (with-provenance "loom.seed.text/extract-json" 1
    (let [start (or (str/index-of text "{") (str/index-of text "["))]
      (when start
        (json/parse-string (subs text start) true)))))

(defn ^{:doc "Convert a string to a URL-friendly slug."
        :tags ["text" "string" "url"]}
  slugify
  [s] (-> s clojure.string/lower-case (clojure.string/replace #"[^a-z0-9]+" "-")))