(ns loom.kg
  "Project-scoped knowledge graph: entity/relation CRUD and queries.
   Authoritative API — see plans/architecture.md §3-§4.

   Identity:
     - Every entity / relation has a single `id VARCHAR PRIMARY KEY`.
     - When :id is omitted on upsert, a UUID is generated. Callers (e.g.
       future loom.ingest) compute deterministic IDs from source locators
       externally and pass them in.

   Vectors:
     - Stored natively as DuckDB FLOAT[768]. The KG layer accepts a Clojure
       vec-of-floats, serializes to a SQL array literal at write time, and
       parses back to a vec on read.

   Concurrency:
     - All writes serialize through loom.db/write! (single writer queue).
     - Compound writers (loom.tools, loom.blob, loom.scratch) call kg fns
       inside ONE outer write! thunk so FKs hold across statements within
       the bridge."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [loom.db :as db]
            [loom.envelope :refer [with-provenance unwrap!]])
  (:import [java.sql Connection PreparedStatement ResultSet]))

;; ---------------------------------------------------------------------------
;; Lifecycle re-exports — `loom.db` is private (its defs are ^:private).
;; Every other namespace uses these `kg/*` aliases instead of reaching into
;; `loom.db` directly. See plans/architecture.md §7.4.
;; ---------------------------------------------------------------------------

(def connect-file!
  "Re-export of `loom.db/connect-file!`. Open the file-backed conn."
  @#'loom.db/connect-file!)

(def init-schema!
  "Re-export of `loom.db/init-schema!`. Idempotent v2 schema bootstrap."
  @#'loom.db/init-schema!)

(def start-writer!
  "Re-export of `loom.db/start-writer!`. Idempotent."
  @#'loom.db/start-writer!)

(def write!
  "Re-export of `loom.db/write!`. Serialise a thunk through the writer queue."
  @#'loom.db/write!)

;; ---------------------------------------------------------------------------
;; Ontology
;; ---------------------------------------------------------------------------

(def ^:private allowed-kinds
  #{"concept" "tool" "file" "function" "module"
    "business_entity" "external" "agent"})

(def ^:private allowed-predicates
  #{"IS_A" "PART_OF" "HAS_PART" "USES" "DEPENDS_ON" "IMPLEMENTS"
    "DEFINED_IN" "MENTIONED_IN" "CAUSES" "BLOCKS" "RELATES_TO"
    "CONTRADICTS" "SUPERSEDES" "AUTHORED_BY" "ABOUT"})

(defn- normalize-kind [k]
  (when k
    (let [s (name k)]
      (when-not (allowed-kinds s)
        (throw (ex-info "Invalid entity kind"
                        {:kind s :allowed allowed-kinds})))
      s)))

(defn- normalize-predicate [p]
  (when p
    (let [s (name p)]
      (when-not (allowed-predicates s)
        (throw (ex-info "Invalid relation predicate"
                        {:predicate s :allowed allowed-predicates})))
      s)))

;; ---------------------------------------------------------------------------
;; SQL helpers
;; ---------------------------------------------------------------------------

(defn- uuid [] (str (java.util.UUID/randomUUID)))

(defn- vec->sql-literal
  "Serialize a Clojure float vector as a DuckDB FLOAT[768] literal.
   Returns nil for nil/empty input. Floats only — no SQL injection risk."
  [v]
  (when (and v (seq v))
    (str "[" (str/join ", " (map float v)) "]::FLOAT[768]")))

(defn- parse-vector [v]
  (cond
    (nil? v) nil
    (instance? java.sql.Array v) (vec (.getArray v))
    (sequential? v) (vec v)
    :else nil))

(defn- parse-json
  "Parse a JSON column value. DuckDB returns JSON as `org.duckdb.JsonNode`
   (not String); `(str node)` yields valid JSON text."
  [v]
  (when v
    (try (json/parse-string (str v) true)
         (catch Exception _ v))))

(defn exec!
  "Run a parameterised mutating SQL statement. Returns updateCount.
   Public — used by `loom.kg` internals AND by compound writers
   (loom.tools, loom.blob, loom.scratch) that mix raw SQL with
   `kg/upsert-entity*` / `kg/upsert-relation*` inside one db/write! thunk."
  [conn sql & params]
  (with-open [ps (.prepareStatement conn sql)]
    (doseq [[i p] (map-indexed vector params)]
      (.setObject ps (inc i) p))
    (.executeUpdate ps)))

(defn query
  "Run a parameterised SELECT. Returns vec of row maps with lower-cased
   keyword column names. Public for compound writers."
  [conn sql & params]
  (with-open [ps (.prepareStatement conn sql)]
    (doseq [[i p] (map-indexed vector params)]
      (.setObject ps (inc i) p))
    (with-open [rs (.executeQuery ps)]
      (let [md   (.getMetaData rs)
            n    (.getColumnCount md)
            cols (mapv #(.getColumnLabel md (inc %)) (range n))]
        (loop [rows []]
          (if (.next rs)
            (recur (conj rows
                         (into {}
                               (map (fn [c]
                                      [(keyword (str/lower-case c))
                                       (.getObject rs c)])
                                    cols))))
            rows))))))

(defn- entity-row->domain [row]
  (-> row
      (update :aliases parse-json)
      (update :attrs   parse-json)
      (update :vector  parse-vector)))

(defn- relation-row->domain [row]
  (update row :attrs parse-json))

;; ---------------------------------------------------------------------------
;; Writes
;; ---------------------------------------------------------------------------

(defn upsert-entity*
  "In-thunk variant of `upsert-entity!`. Returns the raw id (no envelope,
   no `write!` wrap). For use *inside* a compound `db/write!` thunk
   from bridge writers (loom.tools, loom.blob, loom.scratch, future
   loom.ingest). Calling the public version from inside a write thunk
   would deadlock."
  [ctx entity]
  (let [conn  (:db-conn ctx)
        id    (or (:id entity) (uuid))
        kind  (or (normalize-kind (:kind entity))
                  (throw (ex-info ":kind required" {:entity entity})))
        cname (or (:canonical_name entity)
                  (throw (ex-info ":canonical_name required" {:entity entity})))
        aliases-json (json/encode (or (:aliases entity) []))
        attrs-json   (json/encode (or (:attrs   entity) {}))
        vec-frag     (or (vec->sql-literal (:vector entity)) "NULL")
        retired      (boolean (:retired entity))]
    (exec! conn "DELETE FROM entities WHERE id = ?" id)
    (exec! conn
           (str "INSERT INTO entities
                  (id, kind, canonical_name, aliases, attrs, vector, retired)
                  VALUES (?, ?, ?, ?, ?, " vec-frag ", ?)")
           id kind cname aliases-json attrs-json retired)
    id))

(defn upsert-entity!
  "Insert or update an entity. Idempotent on :id; UUID generated if omitted.
     entity ::= {:id?            -- VARCHAR
                 :kind           -- one of allowed-kinds (required)
                 :canonical_name -- VARCHAR (required)
                 :aliases?       -- vec of strings
                 :attrs?         -- map (JSON-encoded internally)
                 :vector?        -- vec of 768 floats
                 :retired?       -- boolean}
   Returns the entity id.

   Implementation: DELETE + INSERT (preserving id). This sidesteps DuckDB's
   limitations on UPDATE for FLOAT[768] array columns and on ART-indexed
   columns. The two statements are serialized through `db/write!` so other
   writes cannot observe the gap, and id is preserved so app-level
   references in `relations` remain valid."
  [ctx entity]
  (with-provenance "loom.kg/upsert-entity!" 1
    (write!
      (fn [] (upsert-entity* ctx entity)))))

(defn upsert-relation*
  "In-thunk variant of `upsert-relation!`. Returns the raw id."
  [ctx relation]
  (let [conn (:db-conn ctx)
        id   (or (:id relation) (uuid))
        subj (or (:subject_id relation)
                 (throw (ex-info ":subject_id required" {:relation relation})))
        obj  (or (:object_id relation)
                 (throw (ex-info ":object_id required" {:relation relation})))
        pred (or (normalize-predicate (:predicate relation))
                 (throw (ex-info ":predicate required" {:relation relation})))
        attrs-json (json/encode (or (:attrs relation) {}))
        retired    (boolean (:retired relation))
        extant (set (mapv :id
                          (query conn
                                 "SELECT id FROM entities
                                   WHERE retired = false
                                     AND id IN (?, ?)"
                                 subj obj)))]
    (when-not (extant subj)
      (throw (ex-info "subject_id does not reference an existing entity"
                      {:subject_id subj :relation relation})))
    (when-not (extant obj)
      (throw (ex-info "object_id does not reference an existing entity"
                      {:object_id obj :relation relation})))
    (exec! conn
           "INSERT INTO relations
              (id, subject_id, predicate, object_id, attrs, retired)
              VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
              subject_id = EXCLUDED.subject_id,
              predicate  = EXCLUDED.predicate,
              object_id  = EXCLUDED.object_id,
              attrs      = EXCLUDED.attrs,
              retired    = EXCLUDED.retired,
              updated_at = now()"
           id subj pred obj attrs-json retired)
    id))

(defn upsert-relation!
  "Insert or update a relation. Idempotent on :id; UUID generated if omitted.
     relation ::= {:id?         -- VARCHAR
                   :subject_id  -- entities(id) (required, must exist)
                   :predicate   -- one of allowed-predicates (required)
                   :object_id   -- entities(id) (required, must exist)
                   :attrs?      -- map
                   :retired?    -- boolean}

   Endpoint existence is enforced at the API layer (DB-level FK is omitted
   to permit `merge-entities!` to UPDATE subject_id/object_id; see DDL
   comment in loom.db).  Throws ex-info if either endpoint is missing
   or retired. Returns the relation id."
  [ctx relation]
  (with-provenance "loom.kg/upsert-relation!" 1
    (write!
      (fn [] (upsert-relation* ctx relation)))))

(defn retire-entity!
  "Mark an entity retired. Returns the id."
  [ctx id]
  (with-provenance "loom.kg/retire-entity!" 1
    (write!
      (fn []
        (exec! (:db-conn ctx)
               "UPDATE entities SET retired = true, updated_at = now() WHERE id = ?"
               id)
        id))))

(defn retire-relation!
  "Mark a relation retired. Returns the id."
  [ctx id]
  (with-provenance "loom.kg/retire-relation!" 1
    (write!
      (fn []
        (exec! (:db-conn ctx)
               "UPDATE relations SET retired = true, updated_at = now() WHERE id = ?"
               id)
        id))))

(defn merge-entities!
  "Atomically merge `from-id` into `to-id`. Self-merge is a no-op.
     1. Rewrite relations.subject_id / object_id from from→to.
     2. Union aliases + canonical_name(from) into to.aliases.
     3. Merge attrs (to wins on key conflict).
     4. Mark from retired with attrs.merged_into = to-id.
   Returns {:merged true|false :from id :to id [:reason kw]}."
  [ctx from-id to-id]
  (with-provenance "loom.kg/merge-entities!" 1
    (write!
      (fn []
        (if (= from-id to-id)
          {:merged false :reason :same-id :from from-id :to to-id}
          (let [conn (:db-conn ctx)
                from-row (first (query conn "SELECT * FROM entities WHERE id = ?" from-id))
                to-row   (first (query conn "SELECT * FROM entities WHERE id = ?" to-id))]
            (when-not from-row (throw (ex-info "merge: source not found" {:from-id from-id})))
            (when-not to-row   (throw (ex-info "merge: target not found"   {:to-id   to-id})))
            (let [from-aliases (or (parse-json (:aliases from-row)) [])
                  to-aliases   (or (parse-json (:aliases to-row))   [])
                  from-attrs   (or (parse-json (:attrs   from-row)) {})
                  to-attrs     (or (parse-json (:attrs   to-row))   {})
                  merged-aliases (json/encode
                                  (vec (distinct (concat to-aliases
                                                         from-aliases
                                                         [(:canonical_name from-row)]))))
                  merged-attrs   (json/encode (merge from-attrs to-attrs))
                  retired-attrs  (json/encode (assoc from-attrs :merged_into to-id))]
              (exec! conn "UPDATE relations SET subject_id = ?, updated_at = now() WHERE subject_id = ?"
                     to-id from-id)
              (exec! conn "UPDATE relations SET object_id  = ?, updated_at = now() WHERE object_id  = ?"
                     to-id from-id)
              (exec! conn "UPDATE entities SET aliases = ?, attrs = ?, updated_at = now() WHERE id = ?"
                     merged-aliases merged-attrs to-id)
              (exec! conn "UPDATE entities SET retired = true, attrs = ?, updated_at = now() WHERE id = ?"
                     retired-attrs from-id)
              {:merged true :from from-id :to to-id})))))))

;; ---------------------------------------------------------------------------
;; Reads
;; ---------------------------------------------------------------------------

(def ^:private entity-cols
  "id, kind, canonical_name, aliases, attrs, vector, retired, created_at, updated_at")

(def ^:private relation-cols
  "id, subject_id, predicate, object_id, attrs, retired, created_at, updated_at")

(defn query-entities
  "Filter and return entities. Filter precedence (plans/architecture.md §3):
     1. :vector       -> ORDER BY array_distance, others narrow
     2. :name-prefix  -> ORDER BY canonical_name ASC, others narrow
     3. :ids          -> batch get, others narrow
     4. none          -> ORDER BY updated_at DESC
   :kind and :limit always apply.
   opts ::= {:ids? :kind? :name-prefix? :vector? :limit?}.
   Returns vec of entity maps with parsed JSON / vector."
  [ctx {:keys [ids kind name-prefix vector limit] :or {limit 100}}]
  (with-provenance "loom.kg/query-entities" 1
    (let [conn   (:db-conn ctx)
          knd    (some-> kind normalize-kind)
          wheres (atom ["retired = false"])
          params (atom [])]
      (when knd
        (swap! wheres conj "kind = ?")
        (swap! params conj knd))
      (when name-prefix
        (swap! wheres conj "lower(canonical_name) LIKE lower(?) || '%'")
        (swap! params conj name-prefix))
      (when (seq ids)
        (swap! wheres conj
               (str "id IN (" (str/join "," (repeat (count ids) "?")) ")"))
        (swap! params into ids))
      (let [where-sql (str/join " AND " @wheres)
            ordering  (cond
                        vector      (str " ORDER BY array_distance(vector, "
                                         (vec->sql-literal vector) ")")
                        name-prefix " ORDER BY canonical_name ASC"
                        :else       " ORDER BY updated_at DESC")
            sql (str "SELECT " entity-cols
                     " FROM entities WHERE " where-sql
                     ordering
                     " LIMIT " (int limit))]
        (mapv entity-row->domain (apply query conn sql @params))))))

(defn query-relations
  "Filter relations. opts ::= {:subject-id? :object-id? :predicate? :limit?}.
   Returns vec of relation maps."
  [ctx {:keys [subject-id object-id predicate limit] :or {limit 200}}]
  (with-provenance "loom.kg/query-relations" 1
    (let [conn   (:db-conn ctx)
          pred   (some-> predicate normalize-predicate)
          wheres (atom ["retired = false"])
          params (atom [])]
      (when subject-id (swap! wheres conj "subject_id = ?") (swap! params conj subject-id))
      (when object-id  (swap! wheres conj "object_id  = ?") (swap! params conj object-id))
      (when pred       (swap! wheres conj "predicate  = ?") (swap! params conj pred))
      (let [sql (str "SELECT " relation-cols
                     " FROM relations WHERE " (str/join " AND " @wheres)
                     " ORDER BY updated_at DESC LIMIT " (int limit))]
        (mapv relation-row->domain (apply query conn sql @params))))))

(defn neighbors
  "One-hop neighbors of `id`.
   opts ::= {:direction :out|:in|:both
             :predicates? [..]
             :limit?}.
   Returns vec of relation rows; each row has :neighbor_id added pointing
   at the *other* endpoint."
  [ctx id {:keys [direction predicates limit] :or {direction :out limit 100}}]
  (with-provenance "loom.kg/neighbors" 1
    (let [conn (:db-conn ctx)
          preds (when (seq predicates)
                  (set (map normalize-predicate predicates)))
          dir-clause (case direction
                       :out  "subject_id = ?"
                       :in   "object_id = ?"
                       :both "(subject_id = ? OR object_id = ?)")
          base-params (if (= direction :both) [id id] [id])
          pred-clause (when preds
                        (str " AND predicate IN ("
                             (str/join "," (repeat (count preds) "?"))
                             ")"))
          all-params (vec (concat base-params (or (when preds (vec preds)) [])))
          sql (str "SELECT " relation-cols
                   " FROM relations
                     WHERE retired = false AND " dir-clause
                   (or pred-clause "")
                   " ORDER BY updated_at DESC LIMIT " (int limit))]
      (->> (apply query conn sql all-params)
           (mapv (fn [r]
                   (let [r* (relation-row->domain r)]
                     (assoc r* :neighbor_id (if (= id (:subject_id r*))
                                              (:object_id r*)
                                              (:subject_id r*))))))))))

(defn bfs
  "Breadth-first traversal from `start-id`. Cycle-safe.
   opts ::= {:max-depth? :limit? :undirected?}.
   Returns vec of {:node_id :min_depth} ordered by min_depth ASC."
  [ctx start-id {:keys [max-depth limit undirected?]
                 :or {max-depth 3 limit 200 undirected? false}}]
  (with-provenance "loom.kg/bfs" 1
    (let [conn (:db-conn ctx)
          sql  (if undirected?
                 "WITH RECURSIVE rel_edges AS (
                    SELECT subject_id, object_id, retired FROM relations
                    UNION ALL
                    SELECT object_id  AS subject_id, subject_id AS object_id, retired
                    FROM relations
                  ),
                  walk(node_id, depth, path) AS (
                    SELECT ?::VARCHAR AS node_id,
                           0::INTEGER AS depth,
                           [ ?::VARCHAR ]::VARCHAR[] AS path
                    UNION ALL
                    SELECT r.object_id AS node_id,
                           w.depth + 1 AS depth,
                           array_concat(w.path, [r.object_id]::VARCHAR[]) AS path
                    FROM walk w
                    JOIN rel_edges r ON r.subject_id = w.node_id
                    WHERE w.depth < ?
                      AND r.retired = false
                      AND list_position(w.path, r.object_id) IS NULL
                  )
                  SELECT node_id, MIN(depth) AS min_depth
                  FROM walk
                  GROUP BY node_id
                  ORDER BY min_depth ASC, node_id ASC
                  LIMIT ?"
                 "WITH RECURSIVE walk(node_id, depth, path) AS (
                    SELECT ?::VARCHAR AS node_id,
                           0::INTEGER AS depth,
                           [ ?::VARCHAR ]::VARCHAR[] AS path
                    UNION ALL
                    SELECT r.object_id AS node_id,
                           w.depth + 1 AS depth,
                           array_concat(w.path, [r.object_id]::VARCHAR[]) AS path
                    FROM walk w
                    JOIN relations r ON r.subject_id = w.node_id
                    WHERE w.depth < ?
                      AND r.retired = false
                      AND list_position(w.path, r.object_id) IS NULL
                  )
                  SELECT node_id, MIN(depth) AS min_depth
                  FROM walk
                  GROUP BY node_id
                  ORDER BY min_depth ASC, node_id ASC
                  LIMIT ?")]
      (query conn sql start-id start-id (int max-depth) (int limit)))))

(defn subgraph
  "Return {:nodes [entity ...] :edges [relation ...]} reachable by BFS
   from `start-id` up to `max-depth`. Three SQL roundtrips total.
   opts ::= {:max-depth? :direction :out|:in|:both}."
  [ctx start-id {:keys [max-depth direction]
                 :or {max-depth 3 direction :out}}]
  (with-provenance "loom.kg/subgraph" 1
    (let [conn        (:db-conn ctx)
          undirected? (= direction :both)
          ;; #1 BFS for node ids
          bfs-rows (unwrap! (bfs ctx start-id {:max-depth max-depth
                                               :limit 200
                                               :undirected? undirected?}))
          node-ids (vec (distinct (conj (mapv :node_id bfs-rows) start-id)))]
      (if (empty? node-ids)
        {:nodes [] :edges []}
        (let [placeholders (str/join "," (repeat (count node-ids) "?"))
              ;; #2 hydrate node entities
              nodes-sql (str "SELECT " entity-cols
                             " FROM entities WHERE retired = false
                               AND id IN (" placeholders ")")
              nodes (mapv entity-row->domain (apply query conn nodes-sql node-ids))
              ;; #3 fetch internal edges
              edges-sql (str "SELECT " relation-cols
                             " FROM relations WHERE retired = false
                               AND subject_id IN (" placeholders ")
                               AND object_id  IN (" placeholders ")")
              edges (mapv relation-row->domain
                          (apply query conn edges-sql
                                 (concat node-ids node-ids)))]
          {:nodes nodes :edges edges})))))
