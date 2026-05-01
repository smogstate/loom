(ns loom.db-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [loom.db :as db]
            [loom.envelope :refer [unwrap!]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *ctx* nil)

(defn db-fixture [f]
  (let [tmp  (str (Files/createTempFile "loom-test-" ".db" (into-array FileAttribute [])))
        _    (.delete (java.io.File. tmp))   ;; DuckDB needs a non-existent or valid file
        ds   (db/connect! tmp)
        _    (db/create-schema! ds)
        _    (db/start-writer!)
        tbl  (db/create-session-facts-table! ds "test-session")
        ctx  {:db                  ds
              :session-id          "test-session"
              :session-facts-table tbl}]
    (binding [*ctx* ctx]
      (f))
    (.delete (java.io.File. tmp))))

(use-fixtures :each db-fixture)

(deftest table-empty-and-count
  (is (true? (unwrap! (db/table-empty? *ctx* :tools))))
  (is (= 0 (unwrap! (db/table-count *ctx* :tools)))))

(deftest save-and-search-tool
  (let [vec  (vec (repeat 768 0.1))
        id   (unwrap! (db/save-tool! *ctx* {:name "test/tool"
                                             :doc  "a test tool"
                                             :tags ["test"]
                                             :vector vec
                                             :code "(defn test-tool [] 42)"}))]
    (is (string? id))
    (is (= 1 (unwrap! (db/table-count *ctx* :tools))))
    (let [results (unwrap! (db/search-tools *ctx* vec 5))]
      (is (= 1 (count results)))
      (is (= "test/tool" (:name (first results)))))))

(deftest tool-versioning
  (let [vec (vec (repeat 768 0.2))]
    (unwrap! (db/save-tool! *ctx* {:name "my/fn" :doc "v1" :vector vec :code "v1"}))
    (unwrap! (db/save-tool! *ctx* {:name "my/fn" :doc "v2" :vector vec :code "v2"}))
    ;; only one non-retired version
    (let [results (unwrap! (db/search-tools *ctx* vec 5))]
      (is (= 1 (count results)))
      (is (= "v2" (:doc (first results)))))))

(deftest save-and-search-fact
  (let [vec (vec (repeat 768 0.3))
        id  (unwrap! (db/save-fact! *ctx* {:content    "service runs on 8080"
                                            :vector     vec
                                            :type       "stable"
                                            :tags       ["config"]
                                            :promoted-by "user"
                                            :session-id "test-session"}))]
    (is (string? id))
    (let [results (unwrap! (db/search-facts *ctx* vec 5))]
      (is (= 1 (count results)))
      (is (= "service runs on 8080" (:content (first results)))))))

(deftest log-and-search-event
  (let [vec (vec (repeat 768 0.4))
        id  (unwrap! (db/log-event! *ctx* {:type       "tool-call"
                                            :content    "some code"
                                            :vector     vec
                                            :session-id "test-session"
                                            :agent-id   "agent-1"}))]
    (is (string? id))
    (let [results (unwrap! (db/search-events *ctx* vec 5))]
      (is (= 1 (count results))))))

(deftest session-facts
  (let [vec (vec (repeat 768 0.5))]
    (unwrap! (db/save-session-fact! *ctx* {:content  "observed: port 9090"
                                            :vector   vec
                                            :agent-id "finder"}))
    (let [results (unwrap! (db/search-session-facts *ctx* vec 5))]
      (is (= 1 (count results)))
      (is (= "observed: port 9090" (:content (first results)))))))
