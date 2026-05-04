(ns loom.seed.db
  "DB search tools exposed to agents — search-tools, search-facts, search-chunks."
  (:require [loom.db :as db]
            [loom.graph :as graph]
            [loom.embedder :as embedder]
            [loom.envelope :refer [with-provenance unwrap!]]))

(def ^:private content-limit 300)

(defn- truncate [s]
  (when s
    (if (> (count s) content-limit)
      (str (subs s 0 content-limit) "…")
      s)))

(defn search-tools
  "Semantic search over the tool library. Returns top-k tools."
  {:doc "Search tool library by semantic similarity. Returns top-k tool records."
   :tags ["db" "search" "tools" "memory"]}
  [ctx query]
  (with-provenance "loom.seed.db/search-tools" 1
    (let [vec (unwrap! (embedder/embed ctx query))]
      (unwrap! (db/search-tools ctx vec 5)))))

(defn search-facts
  "Semantic search over global concept entities (facts-compatible response)."
  {:doc "Search global facts by semantic similarity. Returns top-k fact records. Content truncated to 300 chars."
   :tags ["db" "search" "facts" "memory"]}
  [ctx query]
  (with-provenance "loom.seed.db/search-facts" 1
    (->> (unwrap! (graph/search-entities ctx query 5 :kind :concept))
         (mapv (fn [e]
                 {:id       (:id e)
                  :content  (truncate (:canonical_name e))
                  :tags     (get-in e [:attrs :tags])
                  :type     (get-in e [:attrs :type])
                  :vector   (:vector e)
                  :session-id (first (:source_sessions e))})))))

(defn search-chunks
  "Semantic search over blob chunks."
  {:doc "Search blob chunks by semantic similarity. Returns top-k chunk records. Content truncated to 300 chars; use summary field for overview."
   :tags ["db" "search" "chunks" "blob"]}
  [ctx query]
  (with-provenance "loom.seed.db/search-chunks" 1
    (let [vec (unwrap! (embedder/embed ctx query))]
      (mapv #(update % :content truncate)
            (unwrap! (db/search-chunks ctx vec 5))))))
