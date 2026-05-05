(ns loom.blob-test
  "Smoke coverage for loom.blob/ingest! against the file-backed conn."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [loom.db :as db]
            [loom.kg :as kg]
            [loom.blob :as blob]
            [loom.envelope :refer [unwrap!]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *ctx* nil)

(defn- temp-dir []
  (str (Files/createTempDirectory "loom-blob-test-" (into-array FileAttribute []))))

(defn blob-fixture [f]
  (let [ldir    (temp-dir)
        db-conn (kg/connect-file! ldir)]
    (kg/init-schema! db-conn)
    (kg/start-writer!)
    (binding [*ctx* {:db-conn db-conn :session-id "s1" :config {:loom-dir ldir}}]
      (try (f)
           (finally (.close db-conn))))))

(use-fixtures :each blob-fixture)

(deftest ingest-writes-blob-row-and-gzip-file
  (let [id (unwrap! (blob/ingest! *ctx* "hello world" {:source "test"}))
        conn (:db-conn *ctx*)
        rows (kg/query conn "SELECT id, source, size_bytes FROM blobs")]
    (is (string? id))
    (is (= 1 (count rows)))
    (is (= "test" (:source (first rows))))
    (is (= 11 (int (:size_bytes (first rows)))))
    (testing "gzip file written under loom-dir/blobs/"
      (let [ldir (get-in *ctx* [:config :loom-dir])
            blob-tree (io/file ldir "blobs")]
        (is (.exists blob-tree))))))

(deftest ingest-is-idempotent-on-content
  (let [id1 (unwrap! (blob/ingest! *ctx* "same content" {:source "a"}))
        id2 (unwrap! (blob/ingest! *ctx* "same content" {:source "b"}))
        conn (:db-conn *ctx*)
        rows (kg/query conn "SELECT id FROM blobs")]
    (is (= id1 id2) "same payload should produce same sha256 id")
    (is (= 1 (count rows))
        "second ingest of identical payload should overwrite, not duplicate")))

(deftest ingest-uses-single-write-thunk
  (let [calls (atom 0)
        orig  kg/write!]
    (with-redefs [kg/write! (fn [f] (swap! calls inc) (orig f))]
      (unwrap! (blob/ingest! *ctx* "payload" {:source "x"})))
    (is (= 1 @calls))))
