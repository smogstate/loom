(ns loom.db
  "DuckDB connection, schema initialisation, and the single-writer queue.

   This namespace is the storage gateway for Loom v2 — see
   `plans/architecture.md` §2 for the full DDL and §6 for the cutover
   that produced this shape.

   - `connect-file!` opens the file-backed `<loom-dir>/loom.db` and
     ATTACHes `<loom-dir>/usage.db` as `loom_usage`.
   - `init-schema!` issues the v2 DDL (idempotent; uses CREATE … IF NOT EXISTS).
   - `write!` is the one serialisation point for all mutating writes. Bridge
     writers (loom.kg, loom.audit, loom.tools, loom.blob, loom.scratch,
     loom.budget) wrap their compound writes in a single `write!` thunk
     so foreign-key-like invariants hold across statements.

   Low-level SQL helpers (`exec!`, `query`) live in `loom.kg` and are reused
   from there by the bridge writers; this namespace is intentionally small."
  (:require [clojure.java.io :as io]
            [clojure.core.async :as async])
  (:import [java.sql DriverManager]))

;; ---------------------------------------------------------------------------
;; Connection
;; ---------------------------------------------------------------------------

(defn ^:private connect-file!
  "Open a file-backed DuckDB connection at <loom-dir>/loom.db and ATTACH
   <loom-dir>/usage.db AS loom_usage. Creates parent dirs as needed.
   Returns a java.sql.Connection."
  [loom-dir]
  (Class/forName "org.duckdb.DuckDBDriver")
  (.mkdirs (io/file loom-dir))
  (let [main-path  (str loom-dir "/loom.db")
        usage-path (str loom-dir "/usage.db")
        conn       (DriverManager/getConnection (str "jdbc:duckdb:" main-path))]
    (with-open [stmt (.createStatement conn)]
      (.execute stmt (str "ATTACH IF NOT EXISTS '" usage-path "' AS loom_usage")))
    conn))

;; ---------------------------------------------------------------------------
;; Schema (v2)
;;
;; DuckDB-quirk note (see plans/architecture.md §2):
;; - No secondary indexes anywhere — DuckDB's ART index can't be UPDATEd
;;   and a secondary index on a mutable column produces phantom errors.
;; - No FK on relations.subject_id/object_id or chunks.blob_id, for the
;;   same reason. Endpoint integrity is enforced at the API layer
;;   (loom.kg/upsert-relation!, loom.blob/ingest!).
;; ---------------------------------------------------------------------------

(def ^:private v2-schema-statements
  ;; ordered: parent tables precede tables that reference them.
  ["CREATE TABLE IF NOT EXISTS entities (
      id              VARCHAR PRIMARY KEY,
      kind            VARCHAR NOT NULL,
      canonical_name  VARCHAR NOT NULL,
      aliases         JSON,
      attrs           JSON,
      vector          FLOAT[768],
      retired         BOOLEAN DEFAULT false,
      created_at      TIMESTAMP DEFAULT now(),
      updated_at      TIMESTAMP DEFAULT now()
    )"

   "CREATE TABLE IF NOT EXISTS relations (
      id          VARCHAR PRIMARY KEY,
      subject_id  VARCHAR NOT NULL,
      predicate   VARCHAR NOT NULL,
      object_id   VARCHAR NOT NULL,
      attrs       JSON,
      retired     BOOLEAN DEFAULT false,
      created_at  TIMESTAMP DEFAULT now(),
      updated_at  TIMESTAMP DEFAULT now()
    )"

   "CREATE TABLE IF NOT EXISTS tools (
      id          VARCHAR PRIMARY KEY,
      name        VARCHAR NOT NULL,
      doc         VARCHAR,
      tags        JSON,
      vector      FLOAT[768],
      code        VARCHAR,
      version     INTEGER DEFAULT 1,
      supersedes  VARCHAR,
      retired     BOOLEAN DEFAULT false,
      created_at  TIMESTAMP DEFAULT now()
    )"

   "CREATE TABLE IF NOT EXISTS scratch_tools (
      id          VARCHAR PRIMARY KEY,
      name        VARCHAR NOT NULL UNIQUE,
      doc         VARCHAR,
      tags        JSON,
      vector      FLOAT[768],
      code        VARCHAR,
      file        VARCHAR,
      retired     BOOLEAN DEFAULT false,
      created_at  TIMESTAMP DEFAULT now()
    )"

   "CREATE TABLE IF NOT EXISTS hits (
      tool_name VARCHAR PRIMARY KEY,
      count     INTEGER DEFAULT 0
    )"

   "CREATE TABLE IF NOT EXISTS blobs (
      id          VARCHAR PRIMARY KEY,
      path        VARCHAR,
      source      VARCHAR,
      size_bytes  BIGINT,
      ts          TIMESTAMP DEFAULT now()
    )"

   "CREATE TABLE IF NOT EXISTS chunks (
      id            VARCHAR PRIMARY KEY,
      blob_id       VARCHAR NOT NULL,
      chunk_offset  INTEGER,
      vector        FLOAT[768],
      summary       VARCHAR,
      content       VARCHAR
    )"

   "CREATE TABLE IF NOT EXISTS audit (
      id          VARCHAR PRIMARY KEY,
      ts          TIMESTAMP DEFAULT now(),
      session_id  VARCHAR,
      agent_id    VARCHAR,
      type        VARCHAR NOT NULL,
      content     VARCHAR,
      provenance  JSON
    )"

   ;; attached database — usage telemetry
   "CREATE TABLE IF NOT EXISTS loom_usage.usage (
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
    )"])

(defn ^:private init-schema!
  "Issue CREATE TABLE IF NOT EXISTS for all v2 tables. Idempotent.
   The conn must be obtained from `connect-file!` so the `loom_usage`
   attach is in scope."
  [conn]
  (with-open [stmt (.createStatement conn)]
    (doseq [sql v2-schema-statements]
      (.execute stmt sql))))

;; ---------------------------------------------------------------------------
;; Single-writer queue
;; ---------------------------------------------------------------------------

(defonce ^:private write-ch (async/chan 256))
(defonce ^:private writer-started? (atom false))

;; Sentinels: core.async channels do not transport nil. We translate
;; (1) a thunk returning nil and (2) a thunk throwing into sentinel records,
;; then unwrap on the caller's side.

(defrecord ^:private NilSentinel [])
(defrecord ^:private ErrSentinel [^Throwable t])

(def ^:private nil-sentinel (->NilSentinel))

(defn ^:private start-writer!
  "Start the serialised writer go-loop. Idempotent.

   Wraps every thunk in `try (catch Throwable …)` — Errors and
   AssertionErrors must not silently kill the goroutine, otherwise
   subsequent writes hang forever waiting on a result-ch that never fills."
  []
  (when (compare-and-set! writer-started? false true)
    (async/go-loop []
      (when-let [{:keys [f result-ch]} (async/<! write-ch)]
        (let [result (try (f) (catch Throwable t (->ErrSentinel t)))
              to-put (if (nil? result) nil-sentinel result)]
          (when result-ch (async/>! result-ch to-put)))
        (recur)))))

(defn ^:private write!
  "Serialise a thunk through the write queue. Blocks the caller until done.
   The single coordination point for all mutating writes — every bridge
   writer (loom.kg, loom.audit, loom.tools, loom.blob, loom.scratch,
   loom.budget) routes its compound writes through here so that within
   one thunk the file-backed conn observes a self-consistent state."
  [f]
  (let [result-ch (async/promise-chan)]
    (async/>!! write-ch {:f f :result-ch result-ch})
    (let [r (async/<!! result-ch)]
      (cond
        (instance? ErrSentinel r) (throw (.t ^ErrSentinel r))
        (instance? NilSentinel r) nil
        :else                     r))))
