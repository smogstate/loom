(ns loom.db
  "DuckDB-backed Parquet store.
   Global data lives in <loom-dir>/{tools,facts,events,chunks}.parquet.
   Session data lives in <loom-dir>/sessions/<session-id>/{tools,facts,hits}.parquet.
   One persistent DuckDBConnection per context — in-memory, no .db file."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [clojure.core.async :as async]
            [loom.envelope :refer [with-provenance unwrap!]])
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

(defn list-chunks-by-blob
  "Return all chunks for blob-id ordered by chunk_offset ASC."
  [ctx blob-id]
  (with-provenance "loom.db/list-chunks-by-blob" 1
    (let [conn (:conn ctx)
          path (parquet-path (get-in ctx [:config :loom-dir]) :chunks)]
      (if (.exists (io/file path))
        (query conn (str "SELECT id, blob_id, chunk_offset, vector, summary, content
                          FROM read_parquet('" path "')
                          WHERE blob_id = ?
                          ORDER BY chunk_offset ASC")
               blob-id)
        []))))

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

;; ---------------------------------------------------------------------------
;; Knowledge graph — entities / relations (global + session)
;; ---------------------------------------------------------------------------

(def ^:private allowed-entity-kinds
  #{"concept" "tool" "goal" "agent" "file" "external"})

(def ^:private allowed-predicates
  #{"IS_A" "PART_OF" "HAS_PART" "USES" "DEPENDS_ON" "IMPLEMENTS"
    "DEFINED_IN" "MENTIONED_IN" "CAUSES" "BLOCKS" "RELATES_TO"
    "CONTRADICTS" "SUPERSEDES" "AUTHORED_BY" "ABOUT"})

(def ^:private entities-ddl
  "CREATE TABLE IF NOT EXISTS entities (
     id              VARCHAR PRIMARY KEY,
     canonical_name  VARCHAR,
     kind            VARCHAR,
     aliases         VARCHAR,
     attrs           VARCHAR,
     vector          VARCHAR,
     confidence      DOUBLE,
     source_count    INTEGER DEFAULT 1,
     source_sessions VARCHAR,
     created_at      TIMESTAMP DEFAULT now(),
     updated_at      TIMESTAMP DEFAULT now(),
     retired         BOOLEAN DEFAULT false
   )")

(def ^:private relations-ddl
  "CREATE TABLE IF NOT EXISTS relations (
     id              VARCHAR PRIMARY KEY,
     subject_id      VARCHAR,
     predicate       VARCHAR,
     object_id       VARCHAR,
     confidence      DOUBLE,
     source_id       VARCHAR,
     source_table    VARCHAR,
     source_sessions VARCHAR,
     created_at      TIMESTAMP DEFAULT now(),
     updated_at      TIMESTAMP DEFAULT now(),
     retired         BOOLEAN DEFAULT false
   )")

(def ^:private graph-entities-ddl
  "CREATE TABLE IF NOT EXISTS graph_entities (
     id              VARCHAR,
     canonical_name  VARCHAR,
     kind            VARCHAR,
     aliases         VARCHAR,
     attrs           VARCHAR,
     vector          VARCHAR,
     confidence      DOUBLE,
     source_count    INTEGER,
     source_sessions VARCHAR,
     created_at      TIMESTAMP,
     updated_at      TIMESTAMP,
     retired         BOOLEAN,
     scope_rank      INTEGER
   )")

(def ^:private graph-relations-ddl
  "CREATE TABLE IF NOT EXISTS graph_relations (
     id              VARCHAR,
     subject_id      VARCHAR,
     predicate       VARCHAR,
     object_id       VARCHAR,
     confidence      DOUBLE,
     source_id       VARCHAR,
     source_table    VARCHAR,
     source_sessions VARCHAR,
     created_at      TIMESTAMP,
     updated_at      TIMESTAMP,
     retired         BOOLEAN,
     scope_rank      INTEGER
   )")

(defn- entities-path [ctx]
  (parquet-path (get-in ctx [:config :loom-dir]) :entities))

(defn- relations-path [ctx]
  (parquet-path (get-in ctx [:config :loom-dir]) :relations))

(defn- session-entities-path [ctx session-id]
  (session-parquet-path (get-in ctx [:config :loom-dir]) session-id :entities))

(defn- session-relations-path [ctx session-id]
  (session-parquet-path (get-in ctx [:config :loom-dir]) session-id :relations))

(defn- load-entities-table! [conn path]
  (exec! conn "DROP TABLE IF EXISTS entities")
  (exec! conn entities-ddl)
  (when (.exists (io/file path))
    (exec! conn (str "INSERT INTO entities SELECT * FROM read_parquet('" path "')"))))

(defn- flush-entities! [conn path]
  (ensure-dir! (.getParent (io/file path)))
  (exec! conn (str "COPY entities TO '" path "' (FORMAT PARQUET)")))

(defn- load-relations-table! [conn path]
  (exec! conn "DROP TABLE IF EXISTS relations")
  (exec! conn relations-ddl)
  (when (.exists (io/file path))
    (exec! conn (str "INSERT INTO relations SELECT * FROM read_parquet('" path "')"))))

(defn- flush-relations! [conn path]
  (ensure-dir! (.getParent (io/file path)))
  (exec! conn (str "COPY relations TO '" path "' (FORMAT PARQUET)")))

(defn- parse-json-array [v]
  (let [x (cond
            (nil? v) []
            (string? v) (or (parse-json v) [])
            (sequential? v) (vec v)
            :else [])]
    (vec x)))

(defn- parse-json-object [v]
  (cond
    (nil? v) {}
    (map? v) v
    (string? v) (or (parse-json v) {})
    :else {}))

(defn- encode-json-array [xs]
  (json/encode (vec (distinct (remove nil? xs)))))

(defn- normalize-kind [kind]
  (when kind
    (let [k (name kind)]
      (when-not (contains? allowed-entity-kinds k)
        (throw (ex-info "Invalid entity kind" {:kind k :allowed allowed-entity-kinds})))
      k)))

(defn- normalize-predicate [predicate]
  (when predicate
    (let [p (name predicate)]
      (when-not (contains? allowed-predicates p)
        (throw (ex-info "Invalid relation predicate" {:predicate p :allowed allowed-predicates})))
      p)))

(defn- normalize-vector-literal [v]
  (cond
    (nil? v) nil
    (string? v) v
    :else (float-vec->sql v)))

(defn- entity-row->domain [row]
  (-> row
      (update :aliases parse-json-array)
      (update :attrs parse-json-object)
      (update :vector parse-float-vec)
      (update :source_sessions parse-json-array)))

(defn- relation-row->domain [row]
  (-> row
      (update :source_sessions parse-json-array)))

(defn- load-graph-scope!
  "Load session-first then global graph data into in-memory union tables."
  [conn ctx session-id]
  (exec! conn "DROP TABLE IF EXISTS graph_entities")
  (exec! conn graph-entities-ddl)
  (exec! conn "DROP TABLE IF EXISTS graph_relations")
  (exec! conn graph-relations-ddl)
  (when session-id
    (let [spath-e (session-entities-path ctx session-id)
          spath-r (session-relations-path ctx session-id)]
      (when (.exists (io/file spath-e))
        (exec! conn (str "INSERT INTO graph_entities
                          SELECT id, canonical_name, kind, aliases, attrs, vector,
                                 confidence, source_count, source_sessions,
                                 created_at, updated_at, retired, 0 AS scope_rank
                          FROM read_parquet('" spath-e "')")))
      (when (.exists (io/file spath-r))
        (exec! conn (str "INSERT INTO graph_relations
                          SELECT id, subject_id, predicate, object_id,
                                 confidence, source_id, source_table, source_sessions,
                                 created_at, updated_at, retired, 0 AS scope_rank
                          FROM read_parquet('" spath-r "')")))))
  (let [gpath-e (entities-path ctx)
        gpath-r (relations-path ctx)]
    (when (.exists (io/file gpath-e))
      (exec! conn (str "INSERT INTO graph_entities
                        SELECT id, canonical_name, kind, aliases, attrs, vector,
                               confidence, source_count, source_sessions,
                               created_at, updated_at, retired, 1 AS scope_rank
                        FROM read_parquet('" gpath-e "')")))
    (when (.exists (io/file gpath-r))
      (exec! conn (str "INSERT INTO graph_relations
                        SELECT id, subject_id, predicate, object_id,
                               confidence, source_id, source_table, source_sessions,
                               created_at, updated_at, retired, 1 AS scope_rank
                        FROM read_parquet('" gpath-r "')")))))

(defn db-upsert-entity!
  "Upsert an entity row to global or session graph tables.
   opts: {:scope :global|:session :session-id sid}."
  [ctx entity {:keys [scope session-id] :or {scope :global}}]
  (with-provenance "loom.db/db-upsert-entity!" 1
    (write!
      (fn []
        (let [conn         (:conn ctx)
              sid          (or session-id (:session-id ctx))
              path         (if (= scope :session)
                             (session-entities-path ctx sid)
                             (entities-path ctx))
              id           (or (:id entity) (uuid))
              existing-raw (do
                             (load-entities-table! conn path)
                             (first (query conn "SELECT * FROM entities WHERE id = ?" id)))
              existing     (some-> existing-raw entity-row->domain)
              kind         (normalize-kind (or (:kind entity) (:kind existing) "concept"))
              aliases      (encode-json-array
                            (concat (:aliases existing)
                                    (parse-json-array (:aliases entity))))
              attrs        (json/encode
                            (merge (:attrs existing)
                                   (parse-json-object (:attrs entity))))
              source-sids  (encode-json-array
                            (concat (:source_sessions existing)
                                    (parse-json-array (:source_sessions entity))
                                    (when sid [sid])))
              vec-s        (or (normalize-vector-literal (:vector entity))
                               (normalize-vector-literal (:vector existing-raw)))
              confidence   (double (or (:confidence entity)
                                       (:confidence existing)
                                       0.8))
              source-inc   (max 1 (int (or (:source_count entity) 1)))
              source-count (if existing
                             (+ (int (or (:source_count existing) 0)) source-inc)
                             source-inc)
              retired      (boolean (or (:retired entity) false))
              cname        (or (:canonical_name entity) (:canonical_name existing) id)]
          (if existing-raw
            (exec! conn
              "UPDATE entities
                 SET canonical_name = ?, kind = ?, aliases = ?, attrs = ?,
                     vector = ?, confidence = ?, source_count = ?, source_sessions = ?,
                     updated_at = now(), retired = ?
               WHERE id = ?"
              cname kind aliases attrs vec-s confidence source-count source-sids retired id)
            (exec! conn
              "INSERT INTO entities (id, canonical_name, kind, aliases, attrs, vector,
                                     confidence, source_count, source_sessions, retired)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
              id cname kind aliases attrs vec-s confidence source-count source-sids retired))
          (flush-entities! conn path)
          id)))))

(defn db-retire-entity!
  "Mark an entity as retired in global or session scope."
  [ctx entity-id {:keys [scope session-id] :or {scope :global}}]
  (with-provenance "loom.db/db-retire-entity!" 1
    (write!
      (fn []
        (let [conn (:conn ctx)
              sid  (or session-id (:session-id ctx))
              path (if (= scope :session)
                     (session-entities-path ctx sid)
                     (entities-path ctx))]
          (load-entities-table! conn path)
          (exec! conn "UPDATE entities SET retired = true, updated_at = now() WHERE id = ?" entity-id)
          (flush-entities! conn path)
          entity-id)))))

(defn db-upsert-relation!
  "Upsert a relation row to global or session graph tables.
   opts: {:scope :global|:session :session-id sid}."
  [ctx relation {:keys [scope session-id] :or {scope :global}}]
  (with-provenance "loom.db/db-upsert-relation!" 1
    (write!
      (fn []
        (let [conn      (:conn ctx)
              sid       (or session-id (:session-id ctx))
              path      (if (= scope :session)
                          (session-relations-path ctx sid)
                          (relations-path ctx))
              predicate (normalize-predicate (:predicate relation))
              relation-id (or (:id relation) (uuid))]
          (when-not (:subject_id relation)
            (throw (ex-info "Missing relation subject_id" {:relation relation})))
          (when-not (:object_id relation)
            (throw (ex-info "Missing relation object_id" {:relation relation})))
          (load-relations-table! conn path)
          (let [existing-raw (or (first (query conn "SELECT * FROM relations WHERE id = ?" relation-id))
                                 (first (query conn
                                         "SELECT * FROM relations
                                          WHERE subject_id = ? AND predicate = ? AND object_id = ?
                                          ORDER BY created_at DESC
                                          LIMIT 1"
                                         (:subject_id relation) predicate (:object_id relation))))
                existing     (some-> existing-raw relation-row->domain)
                source-sids  (encode-json-array
                              (concat (:source_sessions existing)
                                      (parse-json-array (:source_sessions relation))
                                      (when sid [sid])))
                confidence   (double (or (:confidence relation)
                                         (:confidence existing)
                                         0.7))
                retired      (boolean (or (:retired relation) false))
                rid          (or (:id existing-raw) relation-id)]
            (if existing-raw
              (exec! conn
                "UPDATE relations
                   SET subject_id = ?, predicate = ?, object_id = ?,
                       confidence = ?, source_id = ?, source_table = ?,
                       source_sessions = ?, updated_at = now(), retired = ?
                 WHERE id = ?"
                (:subject_id relation)
                predicate
                (:object_id relation)
                confidence
                (or (:source_id relation) (:source_id existing))
                (or (:source_table relation) (:source_table existing))
                source-sids
                retired
                rid)
              (exec! conn
                "INSERT INTO relations (id, subject_id, predicate, object_id,
                                        confidence, source_id, source_table,
                                        source_sessions, retired)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                rid
                (:subject_id relation)
                predicate
                (:object_id relation)
                confidence
                (:source_id relation)
                (:source_table relation)
                source-sids
                retired))
            (flush-relations! conn path)
            rid))))))

(defn db-get-entity
  "Get an entity by id with session-first semantics.
   If session-id is provided: session entities first, then global fallback."
  [ctx entity-id session-id]
  (with-provenance "loom.db/db-get-entity" 1
    (let [conn (:conn ctx)
          session-path (when session-id (session-entities-path ctx session-id))
          session-hit (when (and session-path (.exists (io/file session-path)))
                        (do
                          (load-entities-table! conn session-path)
                          (first (query conn
                                   "SELECT * FROM entities WHERE id = ? AND retired = false"
                                   entity-id))))]
      (if session-hit
        (entity-row->domain session-hit)
        (let [global-path (entities-path ctx)]
          (when (.exists (io/file global-path))
            (load-entities-table! conn global-path)
            (some-> (first (query conn
                            "SELECT * FROM entities WHERE id = ? AND retired = false"
                            entity-id))
                    entity-row->domain)))))))

(defn db-get-relation
  "Get a relation by id across session-first (optional) then global."
  [ctx relation-id session-id]
  (with-provenance "loom.db/db-get-relation" 1
    (let [conn (:conn ctx)
          session-path (when session-id (session-relations-path ctx session-id))
          session-hit (when (and session-path (.exists (io/file session-path)))
                        (do
                          (load-relations-table! conn session-path)
                          (first (query conn
                                   "SELECT * FROM relations WHERE id = ? AND retired = false"
                                   relation-id))))]
      (if session-hit
        (relation-row->domain session-hit)
        (let [global-path (relations-path ctx)]
          (when (.exists (io/file global-path))
            (load-relations-table! conn global-path)
            (some-> (first (query conn
                            "SELECT * FROM relations WHERE id = ? AND retired = false"
                            relation-id))
                    relation-row->domain)))))))

(defn db-list-entities
  "List entities across scope (:session|:global|:both).
   opts: {:scope :global|:session|:both :session-id sid :kind \"tool\" :limit 100}."
  [ctx {:keys [scope session-id kind limit] :or {scope :both limit 100}}]
  (with-provenance "loom.db/db-list-entities" 1
    (let [conn (:conn ctx)
          sid  (when (#{:session :both} scope) (or session-id (:session-id ctx)))]
      (load-graph-scope! conn ctx sid)
      (let [rows (query conn "SELECT * FROM graph_entities")
            scope-filtered (case scope
                             :session (filter #(= 0 (:scope_rank %)) rows)
                             :global  (filter #(= 1 (:scope_rank %)) rows)
                             rows)
            deduped (->> scope-filtered
                         (sort-by (juxt :scope_rank :updated_at))
                         (reduce (fn [acc r]
                                   (if (contains? acc (:id r)) acc (assoc acc (:id r) r))) {})
                         vals)
            filtered (->> deduped
                          (filter #(not (true? (:retired %))))
                          (filter #(or (nil? kind) (= (name kind) (:kind %))))
                          (take (int limit)))]
        (mapv entity-row->domain filtered)))))

(defn db-search-entities
  "Semantic search over entities with session-first + global fallback dedupe.
   opts: {:session-id sid :kind \"concept\"}."
  [ctx query-vec k {:keys [session-id kind]}]
  (with-provenance "loom.db/db-search-entities" 1
    (let [conn  (:conn ctx)
          vec-s (float-vec->sql query-vec)]
      (load-graph-scope! conn ctx session-id)
      (let [kind-clause (if kind " AND kind = ?" "")
            params      (if kind [(name kind)] [])
            sql         (str
                          "WITH ranked AS (\n"
                          "  SELECT id, canonical_name, kind, aliases, attrs, vector,\n"
                          "         confidence, source_count, source_sessions,\n"
                          "         created_at, updated_at, retired, scope_rank,\n"
                          "         row_number() OVER (PARTITION BY id ORDER BY scope_rank ASC, updated_at DESC) AS rn\n"
                          "  FROM graph_entities\n"
                          "  WHERE retired = false AND vector IS NOT NULL" kind-clause "\n"
                          ")\n"
                          "SELECT id, canonical_name, kind, aliases, attrs, vector,\n"
                          "       confidence, source_count, source_sessions,\n"
                          "       created_at, updated_at, retired\n"
                          "FROM ranked\n"
                          "WHERE rn = 1\n"
                          "ORDER BY array_distance(vector::FLOAT[768], " vec-s "::FLOAT[768])\n"
                          "LIMIT " (int k))]
        (->> (apply query conn sql params)
             (mapv entity-row->domain))))))

(defn db-list-relations
  "List relations across scope (:session|:global|:both).
   opts: {:scope :global|:session|:both :session-id sid :limit 200}."
  [ctx {:keys [scope session-id limit] :or {scope :both limit 200}}]
  (with-provenance "loom.db/db-list-relations" 1
    (let [conn (:conn ctx)
          sid  (when (#{:session :both} scope) (or session-id (:session-id ctx)))]
      (load-graph-scope! conn ctx sid)
      (let [rows (query conn "SELECT * FROM graph_relations")
            scope-filtered (case scope
                             :session (filter #(= 0 (:scope_rank %)) rows)
                             :global  (filter #(= 1 (:scope_rank %)) rows)
                             rows)
            deduped (->> scope-filtered
                         (sort-by (juxt :scope_rank :updated_at))
                         (reduce (fn [acc r]
                                   (if (contains? acc (:id r)) acc (assoc acc (:id r) r))) {})
                         vals)
            filtered (->> deduped
                          (filter #(not (true? (:retired %))))
                          (take (int limit)))]
        (mapv relation-row->domain filtered)))))

(defn db-search-relations
  "Search relations by optional filters.
   opts: {:session-id sid :subject-id s :object-id o :predicate p :limit n}."
  [ctx {:keys [session-id subject-id object-id predicate limit]
        :or {limit 200}}]
  (with-provenance "loom.db/db-search-relations" 1
    (let [conn (:conn ctx)
          pred (when predicate (normalize-predicate predicate))]
      (load-graph-scope! conn ctx session-id)
      (->> (query conn
             "SELECT id, subject_id, predicate, object_id,
                     confidence, source_id, source_table, source_sessions,
                     created_at, updated_at, retired, scope_rank
              FROM graph_relations
              WHERE retired = false")
           (sort-by (juxt :scope_rank :updated_at))
           (filter #(or (nil? subject-id) (= subject-id (:subject_id %))))
           (filter #(or (nil? object-id) (= object-id (:object_id %))))
           (filter #(or (nil? pred) (= pred (:predicate %))))
           (reduce (fn [acc r]
                     (if (contains? acc (:id r)) acc (assoc acc (:id r) r))) {})
           vals
           (take (int limit))
           (mapv relation-row->domain)))))

(defn db-merge-entities!
  "Merge from-id into to-id atomically inside one write! thunk.
   Rewrites relation subject/object references in selected scope.
   opts: {:scope :global|:session :session-id sid}."
  [ctx from-id to-id {:keys [scope session-id] :or {scope :global}}]
  (with-provenance "loom.db/db-merge-entities!" 1
    (write!
      (fn []
        (if (= from-id to-id)
          {:merged false :reason :same-id :from-id from-id :to-id to-id}
          (let [conn          (:conn ctx)
                sid           (or session-id (:session-id ctx))
                entity-path   (if (= scope :session)
                                (session-entities-path ctx sid)
                                (entities-path ctx))
                relation-path (if (= scope :session)
                                (session-relations-path ctx sid)
                                (relations-path ctx))]
            (load-entities-table! conn entity-path)
            (load-relations-table! conn relation-path)
            (let [from-row (first (query conn "SELECT * FROM entities WHERE id = ?" from-id))
                  to-row   (first (query conn "SELECT * FROM entities WHERE id = ?" to-id))]
              (when-not from-row
                (throw (ex-info "merge source entity not found" {:from-id from-id :scope scope})))
              (when-not to-row
                (throw (ex-info "merge target entity not found" {:to-id to-id :scope scope})))
              (let [from-e         (entity-row->domain from-row)
                    to-e           (entity-row->domain to-row)
                    merged-aliases (encode-json-array (concat (:aliases to-e)
                                                              (:aliases from-e)
                                                              [(:canonical_name from-e)]))
                    merged-attrs   (json/encode (merge (:attrs to-e) (:attrs from-e)))
                    merged-sids    (encode-json-array (concat (:source_sessions to-e)
                                                              (:source_sessions from-e)))
                    merged-count   (+ (int (or (:source_count to-e) 0))
                                      (int (or (:source_count from-e) 0)))
                    merged-conf    (max (double (or (:confidence to-e) 0.0))
                                        (double (or (:confidence from-e) 0.0)))]
                (exec! conn
                  "UPDATE entities
                     SET aliases = ?, attrs = ?, source_sessions = ?, source_count = ?,
                         confidence = ?, updated_at = now()
                   WHERE id = ?"
                  merged-aliases merged-attrs merged-sids merged-count merged-conf to-id)
                (exec! conn
                  "UPDATE entities
                     SET retired = true, updated_at = now(), aliases = ?, attrs = ?
                   WHERE id = ?"
                  (encode-json-array (concat (:aliases from-e) [(:canonical_name to-e)]))
                  (json/encode (assoc (:attrs from-e) :merged-into to-id))
                  from-id)
                (exec! conn "UPDATE relations SET subject_id = ?, updated_at = now() WHERE subject_id = ?" to-id from-id)
                (exec! conn "UPDATE relations SET object_id  = ?, updated_at = now() WHERE object_id  = ?" to-id from-id)
                (flush-entities! conn entity-path)
                (flush-relations! conn relation-path)
                {:merged true :from-id from-id :to-id to-id :scope scope}))))))))

(defn db-neighbors
  "Return one-hop neighbors from graph relations.
   opts: {:session-id sid :direction :out|:in|:both :predicates [..] :limit n}."
  [ctx entity-id {:keys [session-id direction predicates limit]
                  :or {direction :out limit 100}}]
  (with-provenance "loom.db/db-neighbors" 1
    (let [conn (:conn ctx)
          pred-set (when (seq predicates)
                     (set (map normalize-predicate predicates)))]
      (load-graph-scope! conn ctx session-id)
      (->> (query conn
             "SELECT id, subject_id, predicate, object_id,
                     confidence, source_id, source_table, source_sessions,
                     created_at, updated_at, retired, scope_rank
              FROM graph_relations
              WHERE retired = false")
           (sort-by (juxt :scope_rank :updated_at))
           (filter (fn [r]
                     (case direction
                       :out  (= entity-id (:subject_id r))
                       :in   (= entity-id (:object_id r))
                       :both (or (= entity-id (:subject_id r))
                                 (= entity-id (:object_id r)))
                       (= entity-id (:subject_id r)))))
           (filter #(or (nil? pred-set) (pred-set (:predicate %))))
           (map (fn [r]
                  (assoc (relation-row->domain r)
                         :neighbor_id (if (= entity-id (:subject_id r))
                                        (:object_id r)
                                        (:subject_id r)))))
           (take (int limit))
           vec))))

(defn db-bfs
  "Breadth-first traversal over in-memory graph_relations with cycle checks.
   opts: {:session-id sid :max-depth 3 :limit 200 :undirected? false}."
  [ctx start-id {:keys [session-id max-depth limit undirected?]
                 :or {max-depth 3 limit 200 undirected? false}}]
  (with-provenance "loom.db/db-bfs" 1
    (let [conn (:conn ctx)]
      (load-graph-scope! conn ctx session-id)
      (let [sql (if undirected?
                  "WITH RECURSIVE rel_edges AS (
                     SELECT subject_id, object_id, retired FROM graph_relations
                     UNION ALL
                     SELECT object_id AS subject_id, subject_id AS object_id, retired
                     FROM graph_relations
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
                     JOIN rel_edges r
                       ON r.subject_id = w.node_id
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
                     JOIN graph_relations r
                       ON r.subject_id = w.node_id
                     WHERE w.depth < ?
                       AND r.retired = false
                       AND list_position(w.path, r.object_id) IS NULL
                   )
                   SELECT node_id, MIN(depth) AS min_depth
                   FROM walk
                   GROUP BY node_id
                   ORDER BY min_depth ASC, node_id ASC
                   LIMIT ?")]
        (query conn sql start-id start-id (int max-depth) (int limit))))))

(defn migrate-facts-to-graph!
  "One-time migration from facts/session_facts parquet into graph tables.
   Migrates global facts and all sessions/<sid>/facts.parquet files.
   Returns counts {:global n :sessions {sid n ...}}."
  [ctx]
  (with-provenance "loom.db/migrate-facts-to-graph!" 1
    (let [loom-dir (get-in ctx [:config :loom-dir])
          gpath    (parquet-path loom-dir :facts)
          conn     (:conn ctx)
          global-count
          (if (.exists (io/file gpath))
            (let [rows (query conn
                        (str "SELECT id, content, vector, type, tags, promoted_by, session_id
                              FROM read_parquet('" gpath "')
                              WHERE retired = false"))]
              (doseq [r rows]
                (let [sid (or (:session_id r) "global")
                      ent-id (or (:id r) (uuid))]
                  (unwrap! (db-upsert-entity! ctx
                            {:id ent-id
                             :canonical_name (or (:content r) ent-id)
                             :kind "concept"
                             :aliases []
                             :attrs {:type (:type r)
                                     :tags (parse-json-array (:tags r))
                                     :promoted_by (:promoted_by r)}
                             :vector (:vector r)
                             :confidence 0.8
                             :source_count 1
                             :source_sessions [sid]}
                            {:scope :global}))
                  (unwrap! (db-upsert-relation! ctx
                            {:subject_id ent-id
                             :predicate "MENTIONED_IN"
                             :object_id ent-id
                             :confidence 0.8
                             :source_id ent-id
                             :source_table "facts"
                             :source_sessions [sid]}
                            {:scope :global}))))
              (count rows))
            0)
          sessions-dir (io/file loom-dir "sessions")
          session-files (if (.exists sessions-dir)
                          (->> (.listFiles sessions-dir)
                               (filter #(.isDirectory %))
                               (map (fn [d]
                                      {:sid (.getName d)
                                       :path (str (.getAbsolutePath d) "/facts.parquet")}))
                               (filter #(-> % :path io/file .exists)))
                          [])
          session-counts
          (reduce (fn [acc {:keys [sid path]}]
                    (let [rows (query conn
                               (str "SELECT id, agent_id, content, vector
                                     FROM read_parquet('" path "')"))]
                      (doseq [r rows]
                        (let [ent-id (or (:id r) (uuid))]
                          (unwrap! (db-upsert-entity! ctx
                                    {:id ent-id
                                     :canonical_name (or (:content r) ent-id)
                                     :kind "concept"
                                     :aliases []
                                     :attrs {:agent_id (:agent_id r)}
                                     :vector (:vector r)
                                     :confidence 0.7
                                     :source_count 1
                                     :source_sessions [sid]}
                                    {:scope :session :session-id sid}))
                          (unwrap! (db-upsert-relation! ctx
                                    {:subject_id ent-id
                                     :predicate "MENTIONED_IN"
                                     :object_id ent-id
                                     :confidence 0.7
                                     :source_id ent-id
                                     :source_table "session_facts"
                                     :source_sessions [sid]}
                                    {:scope :session :session-id sid}))))
                      (assoc acc sid (count rows))))
                  {}
                  session-files)]
      {:global global-count
       :sessions session-counts})))
