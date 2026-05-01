(ns loom.memory
  "Tier-3 global memory: promote session facts to permanent storage,
   suggest promotions, and retire facts that are no longer relevant."
  (:require [clojure.string :as str]
            [loom.db :as db]
            [loom.embedder :as embedder]
            [loom.envelope :refer [with-provenance unwrap!]]))

(defn promote!
  "Promote a fact string to the global facts table.
   opts: {:type :stable|:accumulating :tags [...] :append-to-loom-md true|false}"
  [ctx content opts]
  (with-provenance "loom.memory/promote!" 1
    (let [vec (unwrap! (embedder/embed ctx content))]
      (let [fact-id (unwrap! (db/save-fact! ctx
                               {:content     content
                                :vector      vec
                                :type        (name (or (:type opts) :stable))
                                :tags        (or (:tags opts) [])
                                :promoted-by (or (get-in ctx [:agent :id]) "user")
                                :session-id  (:session-id ctx)}))]
        (when (:append-to-loom-md opts)
          (let [loom-md (str (get-in ctx [:config :loom-dir] ".loom") "/../LOOM.md")]
            (when (.exists (java.io.File. loom-md))
              (spit loom-md
                    (str "\n## Agent discoveries\n- " content "\n")
                    :append true))))
        fact-id))))

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
  "Retire a global fact by id (mark retired; preserved in Parquet)."
  [ctx fact-id]
  (with-provenance "loom.memory/forget!" 1
    (unwrap! (db/retire-fact! ctx fact-id))))

(defn search
  "Semantic search over global facts."
  [ctx query k]
  (with-provenance "loom.memory/search" 1
    (let [vec (unwrap! (embedder/embed ctx query))]
      (unwrap! (db/search-facts ctx vec k)))))
