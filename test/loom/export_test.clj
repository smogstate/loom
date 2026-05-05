(ns loom.export-test
  "Smoke coverage for loom.export — full DB dump and KG-only subset."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [loom.kg :as kg]
            [loom.export :as export]
            [loom.envelope :refer [unwrap!]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *ctx* nil)

(defn- temp-dir []
  (str (Files/createTempDirectory "loom-export-test-" (into-array FileAttribute []))))

(defn export-fixture [f]
  (let [ldir    (temp-dir)
        db-conn (kg/connect-file! ldir)]
    (kg/init-schema! db-conn)
    (kg/start-writer!)
    (binding [*ctx* {:db-conn db-conn :session-id "s1" :config {:loom-dir ldir}}]
      (try (f)
           (finally (.close db-conn))))))

(use-fixtures :each export-fixture)

(deftest export-kg-writes-parquet-files
  (unwrap! (kg/upsert-entity! *ctx* {:id "e1" :kind "concept" :canonical_name "A"}))
  (let [out (temp-dir)
        paths (unwrap! (export/export-kg! *ctx* out))]
    (is (= 2 (count paths)))
    (doseq [p paths]
      (is (.exists (io/file p)) (str "missing " p)))))

(deftest export-full-db
  (unwrap! (kg/upsert-entity! *ctx* {:id "e1" :kind "concept" :canonical_name "A"}))
  (let [out (temp-dir)]
    (unwrap! (export/export! *ctx* out))
    (testing "EXPORT DATABASE produces a directory tree"
      (is (pos? (count (.listFiles (io/file out))))))))
