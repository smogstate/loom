(ns loom.scratch-test
  "Smoke coverage for loom.scratch v2: scratch_tools and hits writes go to
   the file-backed conn; track-hit! is atomic and accumulates globally
   (no session_id partition)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [loom.db :as db]
            [loom.kg :as kg]
            [loom.scratch :as scratch]
            [loom.envelope :refer [unwrap!]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *ctx* nil)

(defn- temp-dir []
  (str (Files/createTempDirectory "loom-scratch-test-" (into-array FileAttribute []))))

(defn scratch-fixture [f]
  (let [ldir    (temp-dir)
        db-conn (kg/connect-file! ldir)]
    (kg/init-schema! db-conn)
    (kg/start-writer!)
    (binding [*ctx* {:db-conn db-conn :session-id "s1" :config {:loom-dir ldir}}]
      (try (f)
           (finally (.close db-conn))))))

(use-fixtures :each scratch-fixture)

;; ---------------------------------------------------------------------------
;; track-hit!
;; ---------------------------------------------------------------------------

(deftest track-hit-increments-from-zero
  (let [r1 (unwrap! (scratch/track-hit! *ctx* "foo/bar"))
        r2 (unwrap! (scratch/track-hit! *ctx* "foo/bar"))
        r3 (unwrap! (scratch/track-hit! *ctx* "foo/bar"))]
    (is (= 1 (:count r1)))
    (is (= 2 (:count r2)))
    (is (= 3 (:count r3)))
    (is (false? (:promote? r1)))))

(deftest track-hit-promote-flag-at-threshold
  (dotimes [_ 4] (unwrap! (scratch/track-hit! *ctx* "x/y")))
  (let [r5 (unwrap! (scratch/track-hit! *ctx* "x/y"))]
    (is (= 5 (:count r5)))
    (is (true? (:promote? r5))
        "5th hit should set :promote? true")))

(deftest track-hit-counts-are-per-tool
  (unwrap! (scratch/track-hit! *ctx* "a"))
  (unwrap! (scratch/track-hit! *ctx* "b"))
  (unwrap! (scratch/track-hit! *ctx* "a"))
  (let [conn (:db-conn *ctx*)
        rows (kg/query conn "SELECT tool_name, count FROM hits ORDER BY tool_name")]
    (is (= [{:tool_name "a" :count 2}
            {:tool_name "b" :count 1}]
           (mapv #(-> % (update :count int)) rows)))))

(deftest track-hit-uses-single-write-thunk
  (let [calls (atom 0)
        orig  kg/write!]
    (with-redefs [kg/write! (fn [f] (swap! calls inc) (orig f))]
      (unwrap! (scratch/track-hit! *ctx* "foo")))
    (is (= 1 @calls))))
