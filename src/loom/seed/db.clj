(ns loom.seed.db
  "DB search tools exposed to agents — search-tools, search-facts, search-chunks."
  (:require [loom.db :as db]
            [loom.embedder :as embedder]
            [loom.envelope :refer [with-provenance unwrap!]]))

(defn search-tools
  "Semantic search over the tool library. Returns top-k tools."
  {:doc "Search tool library by semantic similarity. Returns top-k tool records."
   :tags ["db" "search" "tools" "memory"]}
  [ctx query]
  (with-provenance "loom.seed.db/search-tools" 1
    (let [vec (unwrap! (embedder/embed ctx query))]
      (unwrap! (db/search-tools ctx vec 5)))))

(defn search-facts
  "Semantic search over global facts."
  {:doc "Search global facts by semantic similarity. Returns top-k fact records."
   :tags ["db" "search" "facts" "memory"]}
  [ctx query]
  (with-provenance "loom.seed.db/search-facts" 1
    (let [vec (unwrap! (embedder/embed ctx query))]
      (unwrap! (db/search-facts ctx vec 5)))))

(defn search-chunks
  "Semantic search over blob chunks."
  {:doc "Search blob chunks by semantic similarity. Returns top-k chunk records."
   :tags ["db" "search" "chunks" "blob"]}
  [ctx query]
  (with-provenance "loom.seed.db/search-chunks" 1
    (let [vec (unwrap! (embedder/embed ctx query))]
      (unwrap! (db/search-chunks ctx vec 5)))))
