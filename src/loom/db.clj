(ns loom.db
  "DuckDB-backed Parquet store.
   Global data lives in <loom-dir>/{tools,facts,events,chunks}.parquet.
   Session data lives in <loom-dir>/sessions/<session-id>/{tools,facts,hits}.parquet.
   One persistent DuckDBConnection per context — in-memory, no .db file."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [clojure.core.async :as async]
            [loom.envelope :refer [with-provenance]])
  (:import [org.duckdb DuckDBConnection]
           [java.sql DriverManager ResultSet]))

;; ---------------------------------------------------------------------------
;; Connection
;; ---------------------------------------------------------------------------

(defn connect!
  "Open an in-memory DuckDB connection. Returns a java.sql.Connection."
  []
  (Class/forName "org.duckdb.DuckDBDriver")
  (DriverManager/getConnection "jdbc:duckdb:"))

;; ---------------------------------------------------------------------------
;; Single-writer queue
;; ---------------------------------------------------------------------------

(defonce ^:private write-ch (async/chan 256))
(defonce ^:private writer-started? (atom false))

(defn start-writer!
  "Start the serialized write loop. Idempotent."
  []
  (when (compare-and-set! writer-started? false true)
    (async/go-loop []
      (when-let [{:keys [f result-ch]} (async/<! write-ch)]
        (let [result (try (f) (catch Exception e e))]
          (when result-ch (async/>! result-ch result)))
        (recur)))))

(defn- write!
  "Serialize a thunk through the write queue. Blocks caller until done."
  [f]
  (let [result-ch (async/promise-chan)]
    (async/>!! write-ch {:f f :result-ch result-ch})
    (let [r (async/<!! result-ch)]
      (if (instance? Exception r) (throw r) r))))

;; ---------------------------------------------------------------------------
;; Low-level SQL helpers
;; ---------------------------------------------------------------------------

(defn- exec!
  "Execute a SQL statement on conn. Returns update count."
  [^java.sql.Connection conn sql & params]
  (let [ps (.prepareStatement conn sql)]
    (doseq [[i p] (map-indexed vector params)]
      (.setObject ps (inc i) p))
    (.executeUpdate ps)))

(defn- query
  "Execute a SELECT and return rows as vec of maps."
  [^java.sql.Connection conn sql & params]
  (let [ps  (.prepareStatement conn sql)
        _   (doseq [[i p] (map-indexed vector params)]
              (.setObject ps (inc i) p))
        rs  (.executeQuery ps)
        md  (.getMetaData rs)
        n   (.getColumnCount md)
        cols (mapv #(.getColumnLabel md (inc %)) (range n))]
    (loop [rows []]
      (if (.next rs)
        (recur (conj rows (into {} (map (fn [c] [(keyword (str/lower-case c))
                                                 (.getObject rs c)]) cols))))
        rows))))

(defn- uuid [] (str (java.util.UUID/randomUUID)))

;; ---------------------------------------------------------------------------
;; Parquet path helpers
;; ---------------------------------------------------------------------------

(defn- ensure-dir! [path]
  (.mkdirs (io/file path)))

(defn parquet-path
  "Absolute path to a global parquet file."
  [loom-dir table]
  (str loom-dir "/" (name table) ".parquet"))

(defn session-parquet-path
  "Absolute path to a session-scoped parquet file."
  [loom-dir session-id table]
  (str loom-dir "/sessions/" session-id "/" (name table) ".parquet"))

;; ---------------------------------------------------------------------------
;; Parquet read/write primitives
;;
;; Strategy: load parquet into a temp in-memory table, mutate, COPY back out.
;; read_parquet() is used directly for queries — no temp table needed.
;; ---------------------------------------------------------------------------

(defn- float-vec->sql
  "Serialize a float vector as a DuckDB array literal."
  [v]
  (when v (str "[" (str/join ", " (map float v)) "]")))

(defn- parse-float-vec [v]
  (when v
    (cond
      (instance? java.sql.Array v) (vec (.getArray v))
      (string? v) (mapv #(Float/parseFloat (str/trim %))
                        (-> (str v)
                            (str/replace #"^\[|\]$" "")
                            (str/split #",")))
      :else (vec v))))

(defn- parse-json [v]
  (when v (json/parse-string (str v) true)))

;; ---------------------------------------------------------------------------
;; Tools — global
;; ---------------------------------------------------------------------------

(def ^:private tools-ddl
  "CREATE TABLE IF NOT EXISTS tools (
     id         VARCHAR PRIMARY KEY,
     name       VARCHAR,
     doc        VARCHAR,
     tags       VARCHAR,
     vector     VARCHAR,
     code       VARCHAR,
     version    INTEGER DEFAULT 1,
     supersedes VARCHAR,
     retired    BOOLEAN DEFAULT false,
     created_at TIMESTAMP DEFAULT now()
   )")

(defn- load-tools-table!
  "Load global tools parquet into in-memory table (creates empty if no file)."
  [conn path]
  (exec! conn "DROP TABLE IF EXISTS tools")
  (exec! conn tools-ddl)
  (when (.exists (io/file path))
    (exec! conn (str "INSERT INTO tools SELECT * FROM read_parquet('" path "')"))))

(defn- flush-tools!
  "Write in-memory tools table back to parquet."
  [conn path]
  (ensure-dir! (.getParent (io/file path)))
  (exec! conn (str "COPY tools TO '" path "' (FORMAT PARQUET)")))

(defn save-tool!
  "Insert or update a tool in the global parquet. Returns envelope with tool id."
  [ctx tool]
  (with-provenance "loom.db/save-tool!" 1
    (write!
      (fn []
        (let [conn  (:conn ctx)
              path  (parquet-path (get-in ctx [:config :loom-dir]) :tools)
              id    (or (:id tool) (uuid))
              tags  (json/encode (or (:tags tool) []))
              vec-s (float-vec->sql (:vector tool))]
          (load-tools-table! conn path)
          (exec! conn "UPDATE tools SET retired = true WHERE name = ? AND retired = false" (:name tool))
          (exec! conn
            "INSERT INTO tools (id, name, doc, tags, vector, code, version, supersedes, retired)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, false)"
            id (:name tool) (:doc tool) tags vec-s
            (:code tool) (or (:version tool) 1) (:supersedes tool))
          (flush-tools! conn path)
          id)))))

(defn search-tools
  "Semantic search over non-retired tools. Returns top-k matches."
  [ctx query-vec k]
  (with-provenance "loom.db/search-tools" 1
    (let [conn  (:conn ctx)
          path  (parquet-path (get-in ctx [:config :loom-dir]) :tools)
          vec-s (float-vec->sql query-vec)]
      (load-tools-table! conn path)
      (->> (query conn
             (str "SELECT id, name, doc, tags, vector, code, version
                   FROM tools
                   WHERE retired = false
                   ORDER BY array_distance(vector::FLOAT[768], " vec-s "::FLOAT[768])
                   LIMIT " (int k)))
           (mapv (fn [r] (-> r
                             (update :tags parse-json)
                             (update :vector parse-float-vec))))))))

(defn get-tool
  "Fetch a single non-retired tool by name."
  [ctx name]
  (with-provenance "loom.db/get-tool" 1
    (let [conn (:conn ctx)
          path (parquet-path (get-in ctx [:config :loom-dir]) :tools)]
      (load-tools-table! conn path)
      (some-> (first (query conn "SELECT * FROM tools WHERE name = ? AND retired = false" name))
              (update :tags parse-json)
              (update :vector parse-float-vec)))))

(defn table-empty?
  "True if no non-retired rows exist in the given parquet table."
  [ctx table]
  (with-provenance "loom.db/table-empty?" 1
    (let [path (parquet-path (get-in ctx [:config :loom-dir]) table)]
      (not (.exists (io/file path))))))

(defn table-count
  "Count non-retired rows in a global parquet table."
  [ctx table]
  (with-provenance "loom.db/table-count" 1
    (let [conn (:conn ctx)
          path (parquet-path (get-in ctx [:config :loom-dir]) table)]
      (if (.exists (io/file path))
        (-> (query conn (str "SELECT COUNT(*) AS cnt FROM read_parquet('" path "') WHERE retired = false"))
            first :cnt)
        0))))

(defn rollback-tool!
  "Un-retire a previous tool version; retire the current one."
  [ctx tool-name previous-id]
  (with-provenance "loom.db/rollback-tool!" 1
    (write!
      (fn []
        (let [conn (:conn ctx)
              path (parquet-path (get-in ctx [:config :loom-dir]) :tools)]
          (load-tools-table! conn path)
          (exec! conn "UPDATE tools SET retired = true  WHERE name = ? AND retired = false" tool-name)
          (exec! conn "UPDATE tools SET retired = false WHERE id = ?" previous-id)
          (flush-tools! conn path))))))

;; ---------------------------------------------------------------------------
;; Facts — global
;; ---------------------------------------------------------------------------

(def ^:private facts-ddl
  "CREATE TABLE IF NOT EXISTS facts (
     id          VARCHAR PRIMARY KEY,
     content     VARCHAR,
     vector      VARCHAR,
     type        VARCHAR,
     tags        VARCHAR,
     promoted_by VARCHAR,
     session_id  VARCHAR,
     retired     BOOLEAN DEFAULT false,
     ts          TIMESTAMP DEFAULT now()
   )")

(defn- load-facts-table! [conn path]
  (exec! conn "DROP TABLE IF EXISTS facts")
  (exec! conn facts-ddl)
  (when (.exists (io/file path))
    (exec! conn (str "INSERT INTO facts SELECT * FROM read_parquet('" path "')"))))

(defn- flush-facts! [conn path]
  (ensure-dir! (.getParent (io/file path)))
  (exec! conn (str "COPY facts TO '" path "' (FORMAT PARQUET)")))

(defn save-fact!
  "Insert a global fact. Returns envelope with new fact id."
  [ctx fact]
  (with-provenance "loom.db/save-fact!" 1
    (write!
      (fn []
        (let [conn  (:conn ctx)
              path  (parquet-path (get-in ctx [:config :loom-dir]) :facts)
              id    (or (:id fact) (uuid))
              tags  (json/encode (or (:tags fact) []))
              vec-s (float-vec->sql (:vector fact))]
          (load-facts-table! conn path)
          (exec! conn
            "INSERT INTO facts (id, content, vector, type, tags, promoted_by, session_id)
             VALUES (?, ?, ?, ?, ?, ?, ?)"
            id (:content fact) vec-s (:type fact) tags
            (:promoted-by fact) (:session-id fact))
          (flush-facts! conn path)
          id)))))

(defn search-facts
  "Semantic search over non-retired global facts."
  [ctx query-vec k]
  (with-provenance "loom.db/search-facts" 1
    (let [conn  (:conn ctx)
          path  (parquet-path (get-in ctx [:config :loom-dir]) :facts)
          vec-s (float-vec->sql query-vec)]
      (if (.exists (io/file path))
        (->> (query conn
               (str "SELECT id, content, type, tags, vector, promoted_by, session_id, ts
                     FROM read_parquet('" path "')
                     WHERE retired = false
                     ORDER BY array_distance(vector::FLOAT[768], " vec-s "::FLOAT[768])
                     LIMIT " (int k)))
             (mapv (fn [r] (-> r (update :tags parse-json) (update :vector parse-float-vec)))))
        []))))

(defn retire-fact!
  [ctx fact-id]
  (with-provenance "loom.db/retire-fact!" 1
    (write!
      (fn []
        (let [conn (:conn ctx)
              path (parquet-path (get-in ctx [:config :loom-dir]) :facts)]
          (load-facts-table! conn path)
          (exec! conn "UPDATE facts SET retired = true WHERE id = ?" fact-id)
          (flush-facts! conn path))))))

;; ---------------------------------------------------------------------------
;; Session facts — per-session parquet
;; ---------------------------------------------------------------------------

(def ^:private session-facts-ddl
  "CREATE TABLE IF NOT EXISTS session_facts (
     id       VARCHAR PRIMARY KEY,
     agent_id VARCHAR,
     content  VARCHAR,
     vector   VARCHAR,
     ts       TIMESTAMP DEFAULT now()
   )")

(defn- session-facts-path [ctx]
  (session-parquet-path
    (get-in ctx [:config :loom-dir])
    (:session-id ctx)
    :facts))

(defn- load-session-facts! [conn path]
  (exec! conn "DROP TABLE IF EXISTS session_facts")
  (exec! conn session-facts-ddl)
  (when (.exists (io/file path))
    (exec! conn (str "INSERT INTO session_facts SELECT * FROM read_parquet('" path "')"))))

(defn- flush-session-facts! [conn path]
  (ensure-dir! (.getParent (io/file path)))
  (exec! conn (str "COPY session_facts TO '" path "' (FORMAT PARQUET)")))

(defn save-session-fact!
  "Write a fact to the per-session parquet."
  [ctx fact]
  (with-provenance "loom.db/save-session-fact!" 1
    (write!
      (fn []
        (let [conn  (:conn ctx)
              path  (session-facts-path ctx)
              id    (or (:id fact) (uuid))
              vec-s (float-vec->sql (:vector fact))]
          (load-session-facts! conn path)
          (exec! conn
            "INSERT INTO session_facts (id, agent_id, content, vector) VALUES (?, ?, ?, ?)"
            id (:agent-id fact) (:content fact) vec-s)
          (flush-session-facts! conn path)
          id)))))

(defn search-session-facts
  "Semantic search within the current session facts parquet."
  [ctx query-vec k]
  (with-provenance "loom.db/search-session-facts" 1
    (let [conn  (:conn ctx)
          path  (session-facts-path ctx)
          vec-s (float-vec->sql query-vec)]
      (if (.exists (io/file path))
        (->> (query conn
               (str "SELECT id, agent_id, content, vector, ts
                     FROM read_parquet('" path "')
                     ORDER BY array_distance(vector::FLOAT[768], " vec-s "::FLOAT[768])
                     LIMIT " (int k)))
             (mapv #(update % :vector parse-float-vec)))
        []))))

;; ---------------------------------------------------------------------------
;; Session scratch tools — per-session parquet
;; ---------------------------------------------------------------------------

(def ^:private scratch-tools-ddl
  "CREATE TABLE IF NOT EXISTS scratch_tools (
     id         VARCHAR PRIMARY KEY,
     name       VARCHAR,
     doc        VARCHAR,
     tags       VARCHAR,
     vector     VARCHAR,
     code       VARCHAR,
     file       VARCHAR,
     retired    BOOLEAN DEFAULT false,
     created_at TIMESTAMP DEFAULT now()
   )")

(defn- scratch-tools-path [ctx]
  (session-parquet-path
    (get-in ctx [:config :loom-dir])
    (:session-id ctx)
    :tools))

(defn- load-scratch-tools! [conn path]
  (exec! conn "DROP TABLE IF EXISTS scratch_tools")
  (exec! conn scratch-tools-ddl)
  (when (.exists (io/file path))
    (exec! conn (str "INSERT INTO scratch_tools SELECT * FROM read_parquet('" path "')"))))

(defn- flush-scratch-tools! [conn path]
  (ensure-dir! (.getParent (io/file path)))
  (exec! conn (str "COPY scratch_tools TO '" path "' (FORMAT PARQUET)")))

(defn save-scratch-tool!
  "Register a scratch tool in the session parquet."
  [ctx tool]
  (with-provenance "loom.db/save-scratch-tool!" 1
    (write!
      (fn []
        (let [conn  (:conn ctx)
              path  (scratch-tools-path ctx)
              id    (or (:id tool) (uuid))
              tags  (json/encode (or (:tags tool) []))
              vec-s (float-vec->sql (:vector tool))]
          (load-scratch-tools! conn path)
          (exec! conn
            "INSERT INTO scratch_tools (id, name, doc, tags, vector, code, file, retired)
             VALUES (?, ?, ?, ?, ?, ?, ?, false)"
            id (:name tool) (:doc tool) tags vec-s (:code tool) (:file tool))
          (flush-scratch-tools! conn path)
          id)))))

(defn search-scratch-tools
  "Semantic search over session scratch tools."
  [ctx query-vec k]
  (with-provenance "loom.db/search-scratch-tools" 1
    (let [conn  (:conn ctx)
          path  (scratch-tools-path ctx)
          vec-s (float-vec->sql query-vec)]
      (if (.exists (io/file path))
        (->> (query conn
               (str "SELECT id, name, doc, tags, vector, code, file
                     FROM read_parquet('" path "')
                     WHERE retired = false
                     ORDER BY array_distance(vector::FLOAT[768], " vec-s "::FLOAT[768])
                     LIMIT " (int k)))
             (mapv (fn [r] (-> r (update :tags parse-json) (update :vector parse-float-vec)))))
        []))))

(defn get-scratch-tool
  "Fetch a single scratch tool by name."
  [ctx name]
  (with-provenance "loom.db/get-scratch-tool" 1
    (let [conn (:conn ctx)
          path (scratch-tools-path ctx)]
      (when (.exists (io/file path))
        (some-> (first (query conn
                  (str "SELECT * FROM read_parquet('" path "')
                        WHERE name = ? AND retired = false") name))
                (update :tags parse-json)
                (update :vector parse-float-vec))))))

;; ---------------------------------------------------------------------------
;; Session hit counts — per-session parquet
;; ---------------------------------------------------------------------------

(def ^:private hits-ddl
  "CREATE TABLE IF NOT EXISTS hits (
     tool_name VARCHAR PRIMARY KEY,
     count     INTEGER DEFAULT 0
   )")

(defn- hits-path [ctx]
  (session-parquet-path
    (get-in ctx [:config :loom-dir])
    (:session-id ctx)
    :hits))

(defn- load-hits! [conn path]
  (exec! conn "DROP TABLE IF EXISTS hits")
  (exec! conn hits-ddl)
  (when (.exists (io/file path))
    (exec! conn (str "INSERT INTO hits SELECT * FROM read_parquet('" path "')"))))

(defn- flush-hits! [conn path]
  (ensure-dir! (.getParent (io/file path)))
  (exec! conn (str "COPY hits TO '" path "' (FORMAT PARQUET)")))

(defn inc-hit!
  "Increment the session hit count for a scratch tool. Returns new count."
  [ctx tool-name]
  (with-provenance "loom.db/inc-hit!" 1
    (write!
      (fn []
        (let [conn (:conn ctx)
              path (hits-path ctx)]
          (load-hits! conn path)
          (let [existing (first (query conn "SELECT count FROM hits WHERE tool_name = ?" tool-name))]
            (if existing
              (exec! conn "UPDATE hits SET count = count + 1 WHERE tool_name = ?" tool-name)
              (exec! conn "INSERT INTO hits (tool_name, count) VALUES (?, 1)" tool-name)))
          (flush-hits! conn path)
          (-> (query conn "SELECT count FROM hits WHERE tool_name = ?" tool-name)
              first :count))))))

(defn get-hit-count
  "Get the session hit count for a scratch tool."
  [ctx tool-name]
  (with-provenance "loom.db/get-hit-count" 1
    (let [conn (:conn ctx)
          path (hits-path ctx)]
      (if (.exists (io/file path))
        (or (-> (query conn
                  (str "SELECT count FROM read_parquet('" path "') WHERE tool_name = ?")
                  tool-name)
                first :count)
            0)
        0))))

;; ---------------------------------------------------------------------------
;; Events — global
;; ---------------------------------------------------------------------------

(def ^:private events-ddl
  "CREATE TABLE IF NOT EXISTS events (
     id         VARCHAR PRIMARY KEY,
     session_id VARCHAR,
     agent_id   VARCHAR,
     type       VARCHAR,
     vector     VARCHAR,
     content    VARCHAR,
     provenance VARCHAR,
     ts         TIMESTAMP DEFAULT now(),
     goal_id    VARCHAR,
     thread_id  VARCHAR
   )")

(def ^:private events-cols
  ;; Order must match events-ddl declaration order.
  ["id" "session_id" "agent_id" "type" "vector" "content" "provenance" "ts"
   "goal_id" "thread_id"])

(defn- parquet-columns
  "Return the set of column names present in a parquet file (lower-cased)."
  [conn path]
  (->> (query conn (str "SELECT name FROM parquet_schema('" path "')"))
       (map (comp str/lower-case :name))
       set))

(defn- events-projection
  "Build the SELECT list, replacing missing columns with NULL AS <col>."
  [present]
  (->> events-cols
       (map (fn [c] (if (contains? present c) c (str "NULL AS " c))))
       (str/join ", ")))

(defn- load-events-table! [conn path]
  (exec! conn "DROP TABLE IF EXISTS events")
  (exec! conn events-ddl)
  (when (.exists (io/file path))
    (let [present (parquet-columns conn path)
          select  (events-projection present)]
      (exec! conn
        (str "INSERT INTO events SELECT " select
             " FROM read_parquet('" path "')")))))

(defn- flush-events! [conn path]
  (ensure-dir! (.getParent (io/file path)))
  (exec! conn (str "COPY events TO '" path "' (FORMAT PARQUET)")))

(defn log-event!
  "Append a structural event with provenance.
   Optional :goal-id (business scope), :thread-id (technical scope)."
  [ctx event]
  (with-provenance "loom.db/log-event!" 1
    (write!
      (fn []
        (let [conn  (:conn ctx)
              path  (parquet-path (get-in ctx [:config :loom-dir]) :events)
              id    (or (:id event) (uuid))
              vec-s (float-vec->sql (:vector event))
              prov  (json/encode (or (:provenance event) {}))]
          (load-events-table! conn path)
          (exec! conn
            "INSERT INTO events
               (id, session_id, agent_id, type, vector, content, provenance, goal_id, thread_id)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
            id (:session-id event) (:agent-id event) (:type event)
            vec-s (:content event) prov
            (:goal-id event) (:thread-id event))
          (flush-events! conn path)
          id)))))

(defn search-events
  "Semantic search over events."
  [ctx query-vec k]
  (with-provenance "loom.db/search-events" 1
    (let [conn  (:conn ctx)
          path  (parquet-path (get-in ctx [:config :loom-dir]) :events)
          vec-s (float-vec->sql query-vec)]
      (if (.exists (io/file path))
        (->> (query conn
               (str "SELECT id, session_id, agent_id, type, vector, content, provenance, ts
                     FROM read_parquet('" path "')
                     ORDER BY array_distance(vector::FLOAT[768], " vec-s "::FLOAT[768])
                     LIMIT " (int k)))
             (mapv (fn [r] (-> r
                               (update :provenance parse-json)
                               (update :vector parse-float-vec)))))
        []))))

(defn search-events-in-thread
  "Fetch events tagged with exactly thread-id, optionally filtered by type.
   Pure SQL — no vector search. Pass type=nil to skip type filter.
   Results ordered by ts ASC (chronological)."
  [ctx thread-id type limit]
  (with-provenance "loom.db/search-events-in-thread" 1
    (let [conn (:conn ctx)
          path (parquet-path (get-in ctx [:config :loom-dir]) :events)
          [where params] (if type
                           ["thread_id = ? AND type = ?" [thread-id type]]
                           ["thread_id = ?"              [thread-id]])]
      (if (.exists (io/file path))
        (->> (apply query conn
               (str "SELECT id, session_id, agent_id, type, content, provenance, ts,
                            goal_id, thread_id
                     FROM read_parquet('" path "')
                     WHERE " where "
                     ORDER BY ts ASC
                     LIMIT " (int limit))
               params)
             (mapv #(update % :provenance parse-json)))
        []))))

(defn search-events-by-prefix
  "Fetch events whose thread_id starts with prefix + '/'.
   Use for parent-thread roll-ups (e.g. all repairs under plan/X).
   Results ordered by ts ASC."
  [ctx prefix type limit]
  (with-provenance "loom.db/search-events-by-prefix" 1
    (let [conn (:conn ctx)
          path (parquet-path (get-in ctx [:config :loom-dir]) :events)
          like (str prefix "/%")
          [where params] (if type
                           ["thread_id LIKE ? AND type = ?" [like type]]
                           ["thread_id LIKE ?"              [like]])]
      (if (.exists (io/file path))
        (->> (apply query conn
               (str "SELECT id, session_id, agent_id, type, content, provenance, ts,
                            goal_id, thread_id
                     FROM read_parquet('" path "')
                     WHERE " where "
                     ORDER BY ts ASC
                     LIMIT " (int limit))
               params)
             (mapv #(update % :provenance parse-json)))
        []))))

;; ---------------------------------------------------------------------------
;; Usage — global (loom.budget)
;; ---------------------------------------------------------------------------

(def ^:private usage-ddl
  "CREATE TABLE IF NOT EXISTS usage (
     id          VARCHAR PRIMARY KEY,
     ts          TIMESTAMP DEFAULT now(),
     session_id  VARCHAR,
     agent_id    VARCHAR,
     op          VARCHAR,
     version     INTEGER,
     duration_ms BIGINT,
     ok          BOOLEAN,
     usd_cost    DOUBLE,
     tokens_in   INTEGER,
     tokens_out  INTEGER
   )")

(defn- load-usage-table! [conn path]
  (exec! conn "DROP TABLE IF EXISTS usage")
  (exec! conn usage-ddl)
  (when (.exists (io/file path))
    (exec! conn (str "INSERT INTO usage SELECT * FROM read_parquet('" path "')"))))

(defn- flush-usage! [conn path]
  (ensure-dir! (.getParent (io/file path)))
  (exec! conn (str "COPY usage TO '" path "' (FORMAT PARQUET)")))

(defn save-usage-batch!
  "Append a batch of usage rows. Routed through write! queue."
  [ctx rows]
  (with-provenance "loom.db/save-usage-batch!" 1
    (write!
      (fn []
        (let [conn (:conn ctx)
              path (parquet-path (get-in ctx [:config :loom-dir]) :usage)]
          (load-usage-table! conn path)
          (doseq [r rows]
            (exec! conn
              "INSERT INTO usage
                 (id, session_id, agent_id, op, version,
                  duration_ms, ok, usd_cost, tokens_in, tokens_out)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
              (:id r) (:session_id r) (:agent_id r) (:op r) (:version r)
              (:duration_ms r) (:ok r) (:usd_cost r) (:tokens_in r) (:tokens_out r)))
          (flush-usage! conn path)
          (count rows))))))

(defn query-usage-scalar
  "Run a parameterised SELECT that returns a single aggregate row against
   the in-memory usage table (after loading from parquet). Returns first row map
   or nil."
  [ctx sql & params]
  (with-provenance "loom.db/query-usage-scalar" 1
    (let [conn (:conn ctx)
          path (parquet-path (get-in ctx [:config :loom-dir]) :usage)]
      (load-usage-table! conn path)
      (first (apply query conn sql params)))))

(defn query-usage-raw
  "Run a parameterised SELECT against usage table. Returns vec of row maps."
  [ctx sql & params]
  (with-provenance "loom.db/query-usage-raw" 1
    (let [conn (:conn ctx)
          path (parquet-path (get-in ctx [:config :loom-dir]) :usage)]
      (load-usage-table! conn path)
      (vec (apply query conn sql params)))))

;; ---------------------------------------------------------------------------
;; Chunks — global
;; ---------------------------------------------------------------------------

(def ^:private chunks-ddl
  "CREATE TABLE IF NOT EXISTS chunks (
     id           VARCHAR PRIMARY KEY,
     blob_id      VARCHAR,
     chunk_offset INTEGER,
     vector       VARCHAR,
     summary      VARCHAR,
     content      VARCHAR
   )")

(defn- load-chunks-table! [conn path]
  (exec! conn "DROP TABLE IF EXISTS chunks")
  (exec! conn chunks-ddl)
  (when (.exists (io/file path))
    (exec! conn (str "INSERT INTO chunks SELECT * FROM read_parquet('" path "')"))))

(defn- flush-chunks! [conn path]
  (ensure-dir! (.getParent (io/file path)))
  (exec! conn (str "COPY chunks TO '" path "' (FORMAT PARQUET)")))

(defn save-chunk!
  "Store a chunk record."
  [ctx chunk]
  (with-provenance "loom.db/save-chunk!" 1
    (write!
      (fn []
        (let [conn  (:conn ctx)
              path  (parquet-path (get-in ctx [:config :loom-dir]) :chunks)
              id    (or (:id chunk) (uuid))
              vec-s (float-vec->sql (:vector chunk))]
          (load-chunks-table! conn path)
          (exec! conn
            "INSERT INTO chunks (id, blob_id, chunk_offset, vector, summary, content)
             VALUES (?, ?, ?, ?, ?, ?)"
            id (:blob-id chunk) (:offset chunk) vec-s (:summary chunk) (:content chunk))
          (flush-chunks! conn path)
          id)))))

(defn search-chunks
  "Semantic search over blob chunks."
  [ctx query-vec k]
  (with-provenance "loom.db/search-chunks" 1
    (let [conn  (:conn ctx)
          path  (parquet-path (get-in ctx [:config :loom-dir]) :chunks)
          vec-s (float-vec->sql query-vec)]
      (if (.exists (io/file path))
        (query conn
          (str "SELECT c.id, c.blob_id, c.chunk_offset, c.vector, c.summary, c.content
                FROM read_parquet('" path "') c
                ORDER BY array_distance(c.vector::FLOAT[768], " vec-s "::FLOAT[768])
                LIMIT " (int k)))
        []))))

(defn completed-offsets
  "Return set of already-processed chunk offsets for a blob."
  [ctx blob-id]
  (with-provenance "loom.db/completed-offsets" 1
    (let [conn (:conn ctx)
          path (parquet-path (get-in ctx [:config :loom-dir]) :chunks)]
      (if (.exists (io/file path))
        (->> (query conn
               (str "SELECT chunk_offset FROM read_parquet('" path "') WHERE blob_id = ?")
               blob-id)
             (map :chunk_offset)
             set)
        #{}))))

;; ---------------------------------------------------------------------------
;; Blobs — global
;; ---------------------------------------------------------------------------

(def ^:private blobs-ddl
  "CREATE TABLE IF NOT EXISTS blobs (
     id         VARCHAR PRIMARY KEY,
     path       VARCHAR,
     source     VARCHAR,
     agent_id   VARCHAR,
     size_bytes BIGINT,
     ts         TIMESTAMP DEFAULT now()
   )")

(defn- load-blobs-table! [conn path]
  (exec! conn "DROP TABLE IF EXISTS blobs")
  (exec! conn blobs-ddl)
  (when (.exists (io/file path))
    (exec! conn (str "INSERT INTO blobs SELECT * FROM read_parquet('" path "')"))))

(defn- flush-blobs! [conn path]
  (ensure-dir! (.getParent (io/file path)))
  (exec! conn (str "COPY blobs TO '" path "' (FORMAT PARQUET)")))

(defn save-blob!
  "Index blob metadata."
  [ctx blob]
  (with-provenance "loom.db/save-blob!" 1
    (write!
      (fn []
        (let [conn (:conn ctx)
              path (parquet-path (get-in ctx [:config :loom-dir]) :blobs)]
          (load-blobs-table! conn path)
          (exec! conn
            "INSERT INTO blobs (id, path, source, agent_id, size_bytes) VALUES (?, ?, ?, ?, ?)"
            (:id blob) (:path blob) (:source blob) (:agent-id blob) (:size-bytes blob))
          (flush-blobs! conn path)
          (:id blob))))))

(defn get-blob [ctx blob-id]
  (with-provenance "loom.db/get-blob" 1
    (let [conn (:conn ctx)
          path (parquet-path (get-in ctx [:config :loom-dir]) :blobs)]
      (when (.exists (io/file path))
        (first (query conn
                 (str "SELECT * FROM read_parquet('" path "') WHERE id = ?")
                 blob-id))))))

;; ---------------------------------------------------------------------------
;; Goals — global
;; ---------------------------------------------------------------------------

(def ^:private goals-ddl
  "CREATE TABLE IF NOT EXISTS goals (
     id               VARCHAR PRIMARY KEY,
     session_id       VARCHAR,
     parent_id        VARCHAR,
     title            VARCHAR,
     description      VARCHAR,
     success_criteria VARCHAR,
     status           VARCHAR DEFAULT 'open',
     vector           VARCHAR,
     created_at       TIMESTAMP DEFAULT now(),
     updated_at       TIMESTAMP DEFAULT now()
   )")

(def ^:private valid-statuses
  #{"open" "active" "blocked" "done" "abandoned"})

(defn- load-goals-table! [conn path]
  (exec! conn "DROP TABLE IF EXISTS goals")
  (exec! conn goals-ddl)
  (when (.exists (io/file path))
    (exec! conn (str "INSERT INTO goals SELECT id, session_id, parent_id, title, description,
                                               success_criteria, status, vector, created_at, updated_at
                      FROM read_parquet('" path "')"))))

(defn- flush-goals! [conn path]
  (ensure-dir! (.getParent (io/file path)))
  (exec! conn (str "COPY goals TO '" path "' (FORMAT PARQUET)")))

(defn save-goal!
  "Insert a new goal. Returns the goal id."
  [ctx goal]
  (with-provenance "loom.db/save-goal!" 1
    (write!
      (fn []
        (let [conn  (:conn ctx)
              path  (parquet-path (get-in ctx [:config :loom-dir]) :goals)
              id    (or (:id goal) (uuid))
              vec-s (float-vec->sql (:vector goal))]
          (load-goals-table! conn path)
          (exec! conn
            "INSERT INTO goals (id, session_id, parent_id, title, description,
                                success_criteria, status, vector)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            id (:session-id goal) (:parent-id goal) (:title goal) (:description goal)
            (:success-criteria goal) (or (:status goal) "open") vec-s)
          (flush-goals! conn path)
          id)))))

(defn update-goal-status!
  "Transition goal to a new status. Valid: open|active|blocked|done|abandoned."
  [ctx goal-id status]
  (with-provenance "loom.db/update-goal-status!" 1
    (when-not (valid-statuses status)
      (throw (ex-info "Invalid goal status" {:status status :allowed valid-statuses})))
    (write!
      (fn []
        (let [conn (:conn ctx)
              path (parquet-path (get-in ctx [:config :loom-dir]) :goals)]
          (load-goals-table! conn path)
          (exec! conn "UPDATE goals SET status = ?, updated_at = now() WHERE id = ?"
                 status goal-id)
          (flush-goals! conn path)
          goal-id)))))

(defn get-goal
  "Fetch a single goal by id, or nil."
  [ctx goal-id]
  (with-provenance "loom.db/get-goal" 1
    (let [conn (:conn ctx)
          path (parquet-path (get-in ctx [:config :loom-dir]) :goals)]
      (when (.exists (io/file path))
        (first (query conn (str "SELECT * FROM read_parquet('" path "') WHERE id = ?")
                      goal-id))))))

(defn list-goals
  "opts: {:scope :session|:global :session-id sid :statuses [\"open\" ...]}"
  [ctx {:keys [scope session-id statuses]}]
  (with-provenance "loom.db/list-goals" 1
    (let [bad (remove valid-statuses statuses)]
      (when (seq bad)
        (throw (ex-info "Invalid goal status" {:invalid bad :allowed valid-statuses}))))
    (let [conn        (:conn ctx)
          path        (parquet-path (get-in ctx [:config :loom-dir]) :goals)
          status-list (str "(" (str/join "," (map #(str "'" % "'") statuses)) ")")
          where       (cond-> (str "status IN " status-list)
                        (= scope :session) (str " AND session_id = ?"))
          params      (if (= scope :session) [session-id] [])]
      (if (.exists (io/file path))
        (apply query conn (str "SELECT * FROM read_parquet('" path "') WHERE " where
                               " ORDER BY created_at DESC") params)
        []))))

(defn list-goal-children
  "Return all goals whose parent_id = parent-id."
  [ctx parent-id]
  (with-provenance "loom.db/list-goal-children" 1
    (let [conn (:conn ctx)
          path (parquet-path (get-in ctx [:config :loom-dir]) :goals)]
      (if (.exists (io/file path))
        (query conn (str "SELECT * FROM read_parquet('" path "') WHERE parent_id = ?")
               parent-id)
        []))))

(defn list-goal-events
  "Return all events linked to goal-id, ordered by ts ASC."
  [ctx goal-id]
  (with-provenance "loom.db/list-goal-events" 1
    (let [conn (:conn ctx)
          path (parquet-path (get-in ctx [:config :loom-dir]) :events)]
      (if (.exists (io/file path))
        (query conn (str "SELECT * FROM read_parquet('" path "') WHERE goal_id = ?
                          ORDER BY ts ASC") goal-id)
        []))))

(defn link-event-to-goal!
  "Set events.goal_id = goal-id for a specific event."
  [ctx event-id goal-id]
  (with-provenance "loom.db/link-event-to-goal!" 1
    (write!
      (fn []
        (let [conn (:conn ctx)
              path (parquet-path (get-in ctx [:config :loom-dir]) :events)]
          (load-events-table! conn path)
          (exec! conn "UPDATE events SET goal_id = ? WHERE id = ?" goal-id event-id)
           (flush-events! conn path)
          event-id)))))

;; ---------------------------------------------------------------------------
;; Guard helpers — thin pass-throughs for loom.guard/search-denials
;; ---------------------------------------------------------------------------

(defn connection
  "Return the raw DuckDB connection from ctx."
  [ctx]
  (:conn ctx))

(defn events-path
  "Return the absolute path to events.parquet."
  [ctx]
  (parquet-path (get-in ctx [:config :loom-dir]) :events))

(defn query-raw
  "Execute a parameterised SELECT on conn. Returns vec of row maps.
   Thin public wrapper over the private query fn."
  [conn sql params]
  (apply query conn sql params))

