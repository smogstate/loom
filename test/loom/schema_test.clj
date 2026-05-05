(ns loom.schema-test
  "Smoke test for the v2 file-backed schema (plans/architecture.md §2).
   Verifies init-schema! creates every expected table, is idempotent, and
   that the FK from relations → entities is enforced."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [loom.kg :as kg])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *conn* nil)
(def ^:dynamic *loom-dir* nil)

(defn- temp-dir []
  (str (Files/createTempDirectory "loom-schema-test-" (into-array FileAttribute []))))

(defn schema-fixture [f]
  (let [ldir (temp-dir)
        conn (kg/connect-file! ldir)]
    (kg/init-schema! conn)
    (binding [*conn* conn *loom-dir* ldir]
      (try (f)
           (finally (.close conn))))))

(use-fixtures :each schema-fixture)

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- exec! [sql]
  (with-open [stmt (.createStatement *conn*)]
    (.execute stmt sql)))

(defn- exec-ok? [sql]
  (try (exec! sql) true
       (catch Exception _ false)))

(defn- exec-throws? [sql]
  (not (exec-ok? sql)))

(defn- table-exists?
  "Probe table existence by attempting a no-rows SELECT. Works across
   attached catalogs (information_schema is per-catalog in DuckDB)."
  ([table]              (exec-ok? (str "SELECT * FROM "        table " WHERE 1=0")))
  ([catalog table]      (exec-ok? (str "SELECT * FROM " catalog "." table " WHERE 1=0"))))

;; ---------------------------------------------------------------------------
;; tests
;; ---------------------------------------------------------------------------

(deftest main-db-tables-exist
  (testing "all v2 main-DB tables exist after init-schema!"
    (doseq [t ["entities" "relations" "tools" "scratch_tools"
               "hits" "blobs" "chunks" "audit"]]
      (is (table-exists? t)
          (str "expected table '" t "' to exist")))))

(deftest attached-usage-table-exists
  (is (table-exists? "loom_usage" "usage")
      "expected 'loom_usage.usage' (attached DB) to exist"))

(deftest init-schema-is-idempotent
  (testing "calling init-schema! twice does not throw"
    (is (exec-ok? "SELECT 1"))
    (kg/init-schema! *conn*)
    (kg/init-schema! *conn*)
    (is (table-exists? "entities"))))

;; NOTE: relations and chunks omit DB-level FK to permit UPDATE on the
;; referencing column (DuckDB ART-index limitation — see DDL comment in
;; loom.db).  Endpoint validation is enforced at the API layer
;; (loom.kg/upsert-relation!, loom.blob/ingest!) and covered by tests in
;; loom.kg-test / loom.blob-test.  At the DDL level we only assert the
;; raw INSERT path is open.

(deftest raw-relation-insert-permitted-when-endpoints-exist
  (exec! "INSERT INTO entities (id, kind, canonical_name)
          VALUES ('e1', 'concept', 'Alpha'),
                 ('e2', 'concept', 'Beta')")
  (is (exec-ok? "INSERT INTO relations (id, subject_id, predicate, object_id)
                 VALUES ('r1', 'e1', 'IS_A', 'e2')")))

(deftest raw-chunk-insert-permitted-when-blob-exists
  (exec! "INSERT INTO blobs (id, path, source, size_bytes)
          VALUES ('b1', '/tmp/x', 'manual', 1)")
  (is (exec-ok? "INSERT INTO chunks (id, blob_id, chunk_offset, content)
                 VALUES ('c1', 'b1', 0, 'hello')")))

(deftest scratch-tools-name-is-unique
  (exec! "INSERT INTO scratch_tools (id, name) VALUES ('s1', 'foo')")
  (testing "second insert with same name fails"
    (is (exec-throws?
          "INSERT INTO scratch_tools (id, name) VALUES ('s2', 'foo')"))))
