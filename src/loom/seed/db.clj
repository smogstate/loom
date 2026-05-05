(ns loom.seed.db
  "Read-only KG / RAG search tools exposed to agents.
   v2: backed by loom.kg (entities/relations) and the file-backed chunks
   table.  `search-facts` is a thin compatibility shim that filters
   entities to `:kind \"concept\"` — facts are no longer a distinct table."
  (:require [clojure.string :as str]
            [loom.kg :as kg]
            [loom.embedder :as embedder]
            [loom.envelope :refer [with-provenance unwrap!]]))

(def ^:private content-limit 300)

(defn- truncate [s]
  (when s
    (if (> (count s) content-limit)
      (str (subs s 0 content-limit) "…")
      s)))

(defn- vec->sql-literal [v]
  (when (and v (seq v))
    (str "[" (str/join ", " (map float v)) "]::FLOAT[768]")))

(defn search-tools
  "Semantic search over the tool registry. Returns top-k tool entities."
  {:doc "Search the tool registry by semantic similarity. Returns top-k tool records."
   :tags ["db" "search" "tools"]}
  [ctx query]
  (with-provenance "loom.seed.db/search-tools" 1
    (let [vec (unwrap! (embedder/embed ctx query))]
      (unwrap! (kg/query-entities ctx {:vector vec :kind "tool" :limit 5})))))

(defn search-facts
  "Semantic search over `concept` entities (legacy 'facts' compatibility shim)."
  {:doc "Search concept entities by semantic similarity. Returns top-k records. canonical_name truncated to 300 chars."
   :tags ["db" "search" "facts" "concepts"]}
  [ctx query]
  (with-provenance "loom.seed.db/search-facts" 1
    (let [vec (unwrap! (embedder/embed ctx query))]
      (->> (unwrap! (kg/query-entities ctx {:vector vec :kind "concept" :limit 5}))
           (mapv (fn [e]
                   {:id      (:id e)
                    :content (truncate (:canonical_name e))
                    :tags    (get-in e [:attrs :tags])
                    :vector  (:vector e)}))))))

(defn search-chunks
  "Semantic search over blob chunks. Returns top-k chunk records."
  {:doc "Search blob chunks by semantic similarity. Returns top-k chunk records. Content truncated to 300 chars; use the summary field for an overview."
   :tags ["db" "search" "chunks" "blob"]}
  [ctx query]
  (with-provenance "loom.seed.db/search-chunks" 1
    (let [vec  (unwrap! (embedder/embed ctx query))
          conn (:db-conn ctx)
          rows (kg/query conn
                         (str "SELECT id, blob_id, chunk_offset, summary, content
                                 FROM chunks
                                ORDER BY array_distance(vector, " (vec->sql-literal vec) ")
                                LIMIT 5"))]
      (mapv #(update % :content truncate) rows))))
