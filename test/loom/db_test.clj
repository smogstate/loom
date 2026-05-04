(ns loom.db-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [loom.db :as db]
            [loom.graph :as graph]
            [loom.scope :as scope :refer [GLOBAL_SID]]
            [loom.envelope :refer [unwrap!]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *ctx* nil)

(defn- temp-dir []
  (str (Files/createTempDirectory "loom-kg-test-" (into-array FileAttribute []))))

(defn db-fixture [f]
  (let [ldir (temp-dir)
        conn (db/connect!)
        _    (db/start-writer!)
        ctx  {:conn       conn
              :session-id "s1"
              :config     {:loom-dir ldir}}]
    (binding [*ctx* ctx]
      (f))))

(use-fixtures :each db-fixture)

(defn- v [x]
  (vec (repeat 768 x)))

(defn- stack
  "Convenience: build a normalised read-stack ending in GLOBAL_SID."
  [& sids]
  (scope/normalize-stack (vec sids)))

(deftest entity-upsert-and-session-first-lookup
  (let [eid "entity-1"]
    (unwrap! (db/db-upsert-entity! *ctx*
             {:id eid :canonical_name "global-name" :kind "concept" :vector (v 0.1)
              :confidence 0.9 :source_sessions ["g"]}
             {:scope :global}))
    (unwrap! (db/db-upsert-entity! *ctx*
             {:id eid :canonical_name "session-name" :kind "concept" :vector (v 0.2)
              :confidence 0.95 :source_sessions ["s1"]}
             {:scope :session :session-id "s1"}))
    (let [hit (unwrap! (db/db-get-entity *ctx* eid (stack "s1")))]
      (is (= "session-name" (:canonical_name hit))))
    (let [fallback (unwrap! (db/db-get-entity *ctx* eid (stack "other-session")))]
      (is (= "global-name" (:canonical_name fallback))))))

(deftest relation-upsert-and-search
  (unwrap! (db/db-upsert-entity! *ctx* {:id "a" :canonical_name "A" :kind "concept" :vector (v 0.1)} {:scope :global}))
  (unwrap! (db/db-upsert-entity! *ctx* {:id "b" :canonical_name "B" :kind "concept" :vector (v 0.1)} {:scope :global}))
  (unwrap! (db/db-upsert-relation! *ctx*
           {:subject_id "a" :predicate "USES" :object_id "b" :confidence 1.0
            :source_id "src1" :source_table "facts" :source_sessions ["s1"]}
           {:scope :global}))
  (let [rels (unwrap! (db/db-search-relations *ctx* {:session-ids (stack)
                                                     :subject-id "a"
                                                     :predicate "USES"
                                                     :limit 10}))]
    (is (= 1 (count rels)))
    (is (= "b" (:object_id (first rels))))))

(deftest merge-entities-atomic-and-self-noop
  (unwrap! (db/db-upsert-entity! *ctx* {:id "e1" :canonical_name "E1" :kind "concept" :vector (v 0.1)
                                        :source_count 1 :source_sessions ["s1"]}
           {:scope :global}))
  (unwrap! (db/db-upsert-entity! *ctx* {:id "e2" :canonical_name "E2" :kind "concept" :vector (v 0.1)
                                        :source_count 1 :source_sessions ["s2"]}
           {:scope :global}))
  (unwrap! (db/db-upsert-relation! *ctx*
           {:subject_id "e1" :predicate "RELATES_TO" :object_id "e2" :confidence 0.7
            :source_id "r1" :source_table "facts" :source_sessions ["s1"]}
           {:scope :global}))

  (let [noop (unwrap! (db/db-merge-entities! *ctx* "e2" "e2" {:scope :global}))]
    (is (false? (:merged noop))))

  (let [merged  (unwrap! (db/db-merge-entities! *ctx* "e1" "e2" {:scope :global}))
        gstack  (stack)
        e1      (unwrap! (db/db-get-entity *ctx* "e1" gstack))
        e2      (unwrap! (db/db-get-entity *ctx* "e2" gstack))
        rels-e1 (unwrap! (db/db-search-relations *ctx* {:session-ids gstack :subject-id "e1" :limit 10}))
        rels-e2 (unwrap! (db/db-search-relations *ctx* {:session-ids gstack :subject-id "e2" :limit 10}))]
    (is (true? (:merged merged)))
    (is (nil? e1))
    (is (>= (:source_count e2) 2))
    (is (empty? rels-e1))
    (is (seq rels-e2))
    (is (= #{"s1" "s2"} (set (:source_sessions e2))))))

(deftest bfs-traversal-cycle-avoidance
  (doseq [id ["n1" "n2" "n3"]]
    (unwrap! (db/db-upsert-entity! *ctx* {:id id :canonical_name id :kind "concept" :vector (v 0.1)} {:scope :global})))
  (doseq [[s o] [["n1" "n2"] ["n2" "n3"] ["n3" "n1"]]]
    (unwrap! (db/db-upsert-relation! *ctx* {:subject_id s :predicate "RELATES_TO" :object_id o
                                            :confidence 1.0 :source_id (str s "->" o)
                                            :source_table "facts" :source_sessions ["s1"]}
             {:scope :global})))
  (let [walk (unwrap! (db/db-bfs *ctx* "n1" {:session-ids (stack)
                                             :max-depth 4 :limit 20 :undirected? false}))
        ids  (set (map :node_id walk))]
    (is (= #{"n1" "n2" "n3"} ids))))

(deftest auto-promotion-criteria
  (unwrap! (db/db-upsert-entity! *ctx* {:id "p1" :canonical_name "promotable" :kind "concept"
                                        :vector (v 0.11) :confidence 0.9
                                        :source_count 2 :source_sessions ["s1" "s2"]}
           {:scope :session :session-id "s1"}))
  (let [res (unwrap! (graph/auto-promote! *ctx* :session-id "s1"))
        g   (unwrap! (db/db-get-entity *ctx* "p1" (stack)))]
    (is (= 1 (:eligible res)))
    (is (= "promotable" (:canonical_name g)))))

(deftest migrate-facts-to-graph-global-and-session
  (let [v1 (v 0.2)
        _ (unwrap! (db/save-fact! *ctx* {:id "f1" :content "global fact"
                                         :vector v1 :type "stable" :tags ["t1"]
                                         :promoted-by "user" :session-id "s-global"}))
        _ (unwrap! (db/save-session-fact! *ctx* {:id "sf1" :agent-id "agent"
                                                 :content "session fact" :vector v1}))
        mig (unwrap! (db/migrate-facts-to-graph! *ctx*))
        ge  (unwrap! (db/db-get-entity *ctx* "f1" (stack)))
        se  (unwrap! (db/db-get-entity *ctx* "sf1" (stack "s1")))]
    (is (= 1 (:global mig)))
    (is (= "global fact" (:canonical_name ge)))
    (is (= "session fact" (:canonical_name se)))))
