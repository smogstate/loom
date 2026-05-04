(ns loom.graph
  "Knowledge graph orchestration over loom.db entity/relation tables."
  (:require [clojure.string :as str]
            [loom.db :as db]
            [loom.embedder :as embedder]
            [loom.envelope :refer [with-provenance unwrap!]])
  (:import [org.apache.commons.text.similarity JaroWinklerSimilarity]))

(def ^:private allowed-cascade-predicates
  #{"IMPLEMENTS" "USES" "DEPENDS_ON"})

(def ^:private promotion-min-source-count 2)
(def ^:private promotion-min-confidence 0.85)
(def ^:private resolution-cos-threshold 0.92)
(def ^:private resolution-jw-threshold 0.70)

(def ^:private jaro (JaroWinklerSimilarity.))

(defn- cosine-similarity [a b]
  (let [pairs (map vector a b)
        dot (reduce (fn [acc [x y]] (+ acc (* (double x) (double y)))) 0.0 pairs)
        na  (Math/sqrt (reduce (fn [acc x] (+ acc (* (double x) (double x)))) 0.0 a))
        nb  (Math/sqrt (reduce (fn [acc x] (+ acc (* (double x) (double x)))) 0.0 b))]
    (if (or (zero? na) (zero? nb)) 0.0 (/ dot (* na nb)))))

(defn- jaro-similarity [a b]
  (double (.apply jaro (str/lower-case (str a)) (str/lower-case (str b)))))

(defn- distinct-session-count [source-sessions]
  (count (set (or source-sessions []))))

(defn- entity-eligible-for-promotion? [e]
  (and (>= (int (or (:source_count e) 0)) promotion-min-source-count)
       (>= (double (or (:confidence e) 0.0)) promotion-min-confidence)
       (>= (distinct-session-count (:source_sessions e)) 2)))

(defn upsert-entity!
  "Envelope-wrapped entity upsert.
   opts: {:scope :session|:global :session-id sid}."
  [ctx entity opts]
  (with-provenance "loom.graph/upsert-entity!" 1
    (unwrap! (db/db-upsert-entity! ctx entity opts))))

(defn upsert-relation!
  "Envelope-wrapped relation upsert.
   opts: {:scope :session|:global :session-id sid}."
  [ctx relation opts]
  (with-provenance "loom.graph/upsert-relation!" 1
    (unwrap! (db/db-upsert-relation! ctx relation opts))))

(defn search-entities
  "Semantic search entities session-first then global."
  [ctx query k & {:keys [session-id kind]}]
  (with-provenance "loom.graph/search-entities" 1
    (let [vec (unwrap! (embedder/embed ctx query))]
      (unwrap! (db/db-search-entities ctx vec k {:session-id session-id :kind kind})))))

(defn search-entities-by-name
  "Prefix search over entities by canonical_name (case-insensitive). No embedding required."
  [ctx name-prefix & {:keys [session-id limit kind]}]
  (with-provenance "loom.graph/search-entities-by-name" 1
    (unwrap! (db/db-search-entities-by-name ctx name-prefix
                                             {:session-id session-id
                                              :limit (or limit 20)
                                              :kind kind}))))

(defn search-relations
  "Search relations with optional filters."
  [ctx & {:keys [session-id subject-id object-id predicate limit]}]
  (with-provenance "loom.graph/search-relations" 1
    (unwrap! (db/db-search-relations ctx {:session-id session-id
                                          :subject-id subject-id
                                          :object-id object-id
                                          :predicate predicate
                                          :limit (or limit 200)}))))

(defn neighbors
  "Return one-hop neighbors from graph relations."
  [ctx entity-id & {:keys [session-id direction predicates limit]}]
  (with-provenance "loom.graph/neighbors" 1
    (unwrap! (db/db-neighbors ctx entity-id {:session-id session-id
                                             :direction (or direction :out)
                                             :predicates predicates
                                             :limit (or limit 100)}))))

(defn bfs
  "Breadth-first traversal from start entity id."
  [ctx start-id & {:keys [session-id max-depth limit undirected?]}]
  (with-provenance "loom.graph/bfs" 1
    (unwrap! (db/db-bfs ctx start-id {:session-id session-id
                                      :max-depth (or max-depth 3)
                                      :limit (or limit 200)
                                       :undirected? (boolean undirected?)}))))

(defn subgraph
  "Return {:nodes [entity...] :edges [relation...]} for a BFS subgraph from start-id.
   Single call; SQL query count ≤ 3 regardless of graph size.
   opts: {:session-id s :max-depth n :direction :out/:in/:both}."
  [ctx start-id opts]
  (with-provenance "loom.graph/subgraph" 1
    (unwrap! (db/db-subgraph ctx start-id opts))))

(defn neighbor-counts
  "Return {:in n :out n} degree counts for an entity. Uses COUNT(*) SQL — no entity hydration."
  [ctx entity-id & {:keys [session-id]}]
  (with-provenance "loom.graph/neighbor-counts" 1
    (unwrap! (db/db-neighbor-counts ctx entity-id {:session-id session-id}))))

(defn resolve-entity!
  "Resolve a candidate entity against existing entities in scope.
   Returns {:resolved-id id :action :existing|:created}."
  [ctx {:keys [canonical_name kind] :as candidate} {:keys [scope session-id] :or {scope :session}}]
  (with-provenance "loom.graph/resolve-entity!" 1
    (let [name-text (or canonical_name "")
          vec (or (:vector candidate) (unwrap! (embedder/embed ctx name-text)))
          cands (unwrap! (db/db-search-entities ctx vec 10 {:session-id session-id :kind kind}))
          winner (first
                  (filter (fn [e]
                            (and (= (:kind e) (name kind))
                                 (>= (cosine-similarity vec (:vector e)) resolution-cos-threshold)
                                 (>= (jaro-similarity name-text (:canonical_name e)) resolution-jw-threshold)))
                          cands))]
      (if winner
        {:resolved-id (:id winner) :action :existing}
        (let [id (unwrap! (db/db-upsert-entity! ctx (assoc candidate :vector vec)
                                                {:scope scope :session-id session-id}))]
          {:resolved-id id :action :created})))))

(defn merge-entities!
  "Merge from-id into to-id atomically; self-merge is a no-op."
  [ctx from-id to-id & {:keys [scope session-id] :or {scope :global}}]
  (with-provenance "loom.graph/merge-entities!" 1
    (unwrap! (db/db-merge-entities! ctx from-id to-id {:scope scope :session-id session-id}))))

(defn promote-entity!
  "Promote session entity to global and copy selected relations.
   Also recursively promotes neighbors reachable through
   IMPLEMENTS/USES/DEPENDS_ON predicates."
  [ctx entity-id & {:keys [session-id cascade?] :or {cascade? true}}]
  (with-provenance "loom.graph/promote-entity!" 1
    (let [sid (or session-id (:session-id ctx))
          e (unwrap! (db/db-get-entity ctx entity-id sid))]
      (when-not e
        (throw (ex-info "entity not found for promotion" {:entity-id entity-id :session-id sid})))
      (let [global-id (unwrap! (db/db-upsert-entity! ctx (assoc e :id (:id e)) {:scope :global}))]
        (when cascade?
          (let [rels (unwrap! (db/db-search-relations ctx {:session-id sid :subject-id entity-id :limit 500}))]
            (doseq [r rels]
              (let [predicate (:predicate r)]
                (when (contains? allowed-cascade-predicates predicate)
                  (let [obj (unwrap! (db/db-get-entity ctx (:object_id r) sid))]
                    (when obj
                      (unwrap! (db/db-upsert-entity! ctx obj {:scope :global}))
                      (unwrap! (db/db-upsert-relation! ctx
                                (assoc r :source_table (or (:source_table r) "session_facts"))
                                {:scope :global})))))))))
        global-id))))

(defn auto-promote!
  "Auto-promote all session entities passing source/confidence/session thresholds."
  [ctx & {:keys [session-id]}]
  (with-provenance "loom.graph/auto-promote!" 1
    (let [sid (or session-id (:session-id ctx))
          entities (unwrap! (db/db-list-entities ctx {:scope :session :session-id sid :limit 1000}))
          eligible (filter entity-eligible-for-promotion? entities)
          promoted (mapv (fn [e]
                           (unwrap! (promote-entity! ctx (:id e)
                                                     :session-id sid
                                                     :cascade? true)))
                         eligible)]
      {:session-id sid
       :eligible (count eligible)
       :promoted promoted})))

(defn extract-and-link!
  "Lightweight extraction bridge from textual content into session KG.
   Extraction policy: capped to <= 8 inferred triples per call.
   For now uses deterministic heuristic extraction as fallback."
  [ctx {:keys [content source-id source-table session-id]}]
  (with-provenance "loom.graph/extract-and-link!" 1
    (let [sid (or session-id (:session-id ctx))
          lines (->> (str/split-lines (or content ""))
                     (map str/trim)
                     (remove str/blank?)
                     (take 8))
          pairs (map-indexed vector lines)]
      (doseq [[idx line] pairs]
        (let [subj (unwrap! (resolve-entity! ctx {:canonical_name (str "line-" idx)
                                                  :kind :concept
                                                  :aliases []
                                                  :attrs {:text line}}
                                          {:scope :session :session-id sid}))
              obj (unwrap! (resolve-entity! ctx {:canonical_name (subs line 0 (min 48 (count line)))
                                                 :kind :concept
                                                 :aliases []
                                                 :attrs {:snippet line}}
                                         {:scope :session :session-id sid}))]
          (unwrap! (db/db-upsert-relation! ctx
                    {:subject_id (:resolved-id subj)
                     :predicate "RELATES_TO"
                     :object_id (:resolved-id obj)
                     :confidence 0.6
                     :source_id source-id
                     :source_table (or source-table "session_facts")
                     :source_sessions [sid]}
                    {:scope :session :session-id sid}))))
      {:session-id sid
       :triples-created (count pairs)})))
