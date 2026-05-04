(ns loom.memory
  "Tier-3 global memory: promote session facts to permanent storage,
   suggest promotions, and retire facts that are no longer relevant."
  (:require [clojure.string :as str]
            [loom.db :as db]
            [loom.graph :as graph]
            [loom.embedder :as embedder]
            [loom.envelope :refer [with-provenance unwrap!]]))

(defn promote!
  "Promote a fact string to the global graph entities table.
   opts: {:type :stable|:accumulating :tags [...] :append-to-loom-md true|false}"
  [ctx content opts]
  (with-provenance "loom.memory/promote!" 1
    (let [vec (unwrap! (embedder/embed ctx content))]
      (let [entity-id (unwrap! (graph/upsert-entity! ctx
                                {:canonical_name  content
                                 :kind            "concept"
                                 :aliases         []
                                 :attrs           {:type        (name (or (:type opts) :stable))
                                                   :tags        (or (:tags opts) [])
                                                   :promoted_by (or (get-in ctx [:agent :id]) "user")}
                                 :vector          vec
                                 :confidence      0.9
                                 :source_count    1
                                 :source_sessions [(:session-id ctx)]}
                                {:scope :global}))]
        (when (:append-to-loom-md opts)
          (let [loom-md (str (get-in ctx [:config :loom-dir] ".loom") "/../LOOM.md")]
            (when (.exists (java.io.File. loom-md))
              (spit loom-md
                    (str "\n## Agent discoveries\n- " content "\n")
                    :append true))))
        entity-id))))

(defn suggest-promotion!
  "Print a suggestion to stdout and wait for user input (y/n)."
  [ctx content suggestion-text]
  (with-provenance "loom.memory/suggest-promotion!" 1
    (println (str "\n[LOOM] Promotion suggestion: " suggestion-text))
    (println (str "  Fact: " content))
    (print "  Promote? (y/n): ")
    (flush)
    (let [answer (str/trim (read-line))]
      (if (= "y" answer)
        (unwrap! (promote! ctx content {}))
        :skipped))))

(defn forget!
  "Retire a global entity by id (mark retired; preserved in Parquet)."
  [ctx entity-id]
  (with-provenance "loom.memory/forget!" 1
    (unwrap! (db/db-retire-entity! ctx entity-id {:scope :global}))))

(defn search
  "Semantic search over global concept entities."
  [ctx query k]
  (with-provenance "loom.memory/search" 1
    (unwrap! (graph/search-entities ctx query k :kind :concept))))
