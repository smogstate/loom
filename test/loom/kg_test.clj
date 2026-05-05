(ns loom.kg-test
  "Coverage for loom.kg public API: writes, reads, filter precedence,
   FK enforcement, intra-session merge, BFS, subgraph."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [loom.db :as db]
            [loom.kg :as kg]
            [loom.envelope :refer [unwrap!]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *ctx* nil)

(defn- temp-dir []
  (str (Files/createTempDirectory "loom-kg-test-" (into-array FileAttribute []))))

(defn- v
  "Construct a 768-dim float vector filled with `x`."
  [x] (vec (repeat 768 (float x))))

(defn kg-fixture [f]
  (let [ldir    (temp-dir)
        db-conn (kg/connect-file! ldir)]
    (kg/init-schema! db-conn)
    (kg/start-writer!)
    (binding [*ctx* {:db-conn db-conn :config {:loom-dir ldir}}]
      (try (f)
           (finally (.close db-conn))))))

(use-fixtures :each kg-fixture)

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- mk-entity!
  ([id name] (mk-entity! id name "concept" nil))
  ([id name kind] (mk-entity! id name kind nil))
  ([id name kind vec]
   (unwrap! (kg/upsert-entity! *ctx*
              (cond-> {:id id :kind kind :canonical_name name}
                vec (assoc :vector vec))))))

(defn- mk-rel!
  ([sid pred oid] (mk-rel! nil sid pred oid))
  ([id sid pred oid]
   (unwrap! (kg/upsert-relation! *ctx*
              (cond-> {:subject_id sid :predicate pred :object_id oid}
                id (assoc :id id))))))

;; ---------------------------------------------------------------------------
;; writes
;; ---------------------------------------------------------------------------

(deftest upsert-entity-roundtrip
  (let [id (mk-entity! "e1" "Alpha" "concept" (v 0.1))
        es (unwrap! (kg/query-entities *ctx* {:ids ["e1"]}))]
    (is (= "e1" id))
    (is (= 1 (count es)))
    (is (= "Alpha" (:canonical_name (first es))))
    (is (= "concept" (:kind (first es))))
    (is (= 768 (count (:vector (first es)))))))

(deftest upsert-entity-conflict-updates
  (mk-entity! "e1" "Original")
  (mk-entity! "e1" "Renamed")
  (let [es (unwrap! (kg/query-entities *ctx* {:ids ["e1"]}))]
    (is (= "Renamed" (:canonical_name (first es))))))

(deftest upsert-entity-without-id-generates-uuid
  (let [id1 (unwrap! (kg/upsert-entity! *ctx* {:kind "concept" :canonical_name "X"}))
        id2 (unwrap! (kg/upsert-entity! *ctx* {:kind "concept" :canonical_name "Y"}))]
    (is (string? id1))
    (is (not= id1 id2))))

(deftest upsert-entity-rejects-bad-kind
  (is (thrown? Exception
        (unwrap! (kg/upsert-entity! *ctx* {:kind "garbage" :canonical_name "X"})))))

(deftest upsert-entity-rejects-missing-canonical-name
  (is (thrown? Exception
        (unwrap! (kg/upsert-entity! *ctx* {:kind "concept"})))))

;; ---------------------------------------------------------------------------
;; FK
;; ---------------------------------------------------------------------------

(deftest fk-enforced-on-relation-insert
  (testing "relation with non-existent endpoint throws"
    (is (thrown? Exception
          (unwrap! (kg/upsert-relation! *ctx*
                     {:subject_id "no-such" :predicate "IS_A"
                      :object_id  "also-missing"}))))))

(deftest relation-roundtrip
  (mk-entity! "a" "A")
  (mk-entity! "b" "B")
  (let [rid (mk-rel! "r1" "a" "USES" "b")
        rs  (unwrap! (kg/query-relations *ctx* {:subject-id "a"}))]
    (is (= "r1" rid))
    (is (= 1 (count rs)))
    (is (= "USES" (:predicate (first rs))))))

(deftest relation-rejects-bad-predicate
  (mk-entity! "a" "A")
  (mk-entity! "b" "B")
  (is (thrown? Exception
        (unwrap! (kg/upsert-relation! *ctx*
                   {:subject_id "a" :predicate "WHATEVER" :object_id "b"})))))

;; ---------------------------------------------------------------------------
;; retire
;; ---------------------------------------------------------------------------

(deftest retire-entity-hides-from-default-query
  (mk-entity! "e1" "Alpha")
  (unwrap! (kg/retire-entity! *ctx* "e1"))
  (let [es (unwrap! (kg/query-entities *ctx* {:ids ["e1"]}))]
    (is (empty? es))))

(deftest retire-relation-hides-from-default-query
  (mk-entity! "a" "A") (mk-entity! "b" "B")
  (let [rid (mk-rel! "a" "USES" "b")]
    (unwrap! (kg/retire-relation! *ctx* rid))
    (is (empty? (unwrap! (kg/query-relations *ctx* {:subject-id "a"}))))))

;; ---------------------------------------------------------------------------
;; query-entities — filter precedence
;; ---------------------------------------------------------------------------

(deftest query-by-ids
  (mk-entity! "e1" "A") (mk-entity! "e2" "B") (mk-entity! "e3" "C")
  (let [es (unwrap! (kg/query-entities *ctx* {:ids ["e1" "e3"]}))]
    (is (= #{"e1" "e3"} (set (map :id es))))))

(deftest query-by-kind
  (mk-entity! "c1" "Concept" "concept")
  (mk-entity! "t1" "ToolThing" "tool")
  (let [es (unwrap! (kg/query-entities *ctx* {:kind "tool"}))]
    (is (= 1 (count es)))
    (is (= "t1" (:id (first es))))))

(deftest query-by-name-prefix
  (mk-entity! "e1" "ApplePie")
  (mk-entity! "e2" "Banana")
  (mk-entity! "e3" "ApplePeel")
  (let [es (unwrap! (kg/query-entities *ctx* {:name-prefix "Apple"}))]
    (is (= #{"e1" "e3"} (set (map :id es))))
    ;; ordering should be ASC by canonical_name
    (is (= ["ApplePeel" "ApplePie"] (mapv :canonical_name es)))))

(deftest query-by-vector-orders-by-distance
  (mk-entity! "near"  "near"  "concept" (v 0.1))
  (mk-entity! "far"   "far"   "concept" (v 0.9))
  (mk-entity! "mid"   "mid"   "concept" (v 0.5))
  (let [es (unwrap! (kg/query-entities *ctx* {:vector (v 0.1)}))]
    (is (= ["near" "mid" "far"] (mapv :id es)))))

(deftest query-by-vector-with-kind-narrows
  (mk-entity! "ec" "ConceptOne" "concept" (v 0.1))
  (mk-entity! "et" "ToolOne"    "tool"    (v 0.1))
  (let [es (unwrap! (kg/query-entities *ctx* {:vector (v 0.1) :kind "tool"}))]
    (is (= 1 (count es)))
    (is (= "et" (:id (first es))))))

;; ---------------------------------------------------------------------------
;; query-relations
;; ---------------------------------------------------------------------------

(deftest query-relations-by-predicate
  (mk-entity! "a" "A") (mk-entity! "b" "B") (mk-entity! "c" "C")
  (mk-rel! "a" "USES"        "b")
  (mk-rel! "a" "DEPENDS_ON"  "c")
  (let [rs (unwrap! (kg/query-relations *ctx* {:predicate "USES"}))]
    (is (= 1 (count rs)))
    (is (= "b" (:object_id (first rs))))))

;; ---------------------------------------------------------------------------
;; merge
;; ---------------------------------------------------------------------------

(deftest merge-self-is-noop
  (mk-entity! "e1" "Alpha")
  (let [r (unwrap! (kg/merge-entities! *ctx* "e1" "e1"))]
    (is (false? (:merged r)))
    (is (= :same-id (:reason r)))))

(deftest merge-rewrites-relations
  (mk-entity! "from" "Old")
  (mk-entity! "to"   "New")
  (mk-entity! "x"    "Other")
  (mk-rel! "rA" "from" "USES"        "x")
  (mk-rel! "rB" "x"    "DEPENDS_ON"  "from")
  (let [r (unwrap! (kg/merge-entities! *ctx* "from" "to"))]
    (is (true? (:merged r))))
  (let [out-rs (unwrap! (kg/query-relations *ctx* {:subject-id "to"}))
        in-rs  (unwrap! (kg/query-relations *ctx* {:object-id  "to"}))]
    (is (= 1 (count out-rs)))
    (is (= "x" (:object_id (first out-rs))))
    (is (= 1 (count in-rs)))
    (is (= "x" (:subject_id (first in-rs))))))

(deftest merge-retires-from
  (mk-entity! "from" "Old") (mk-entity! "to" "New")
  (unwrap! (kg/merge-entities! *ctx* "from" "to"))
  (is (empty? (unwrap! (kg/query-entities *ctx* {:ids ["from"]})))))

;; ---------------------------------------------------------------------------
;; neighbors / bfs / subgraph
;; ---------------------------------------------------------------------------

(deftest neighbors-out
  (mk-entity! "a" "A") (mk-entity! "b" "B") (mk-entity! "c" "C")
  (mk-rel! "a" "USES" "b")
  (mk-rel! "a" "USES" "c")
  (mk-rel! "b" "USES" "a")
  (let [ns (unwrap! (kg/neighbors *ctx* "a" {:direction :out}))]
    (is (= #{"b" "c"} (set (map :neighbor_id ns))))))

(deftest neighbors-in
  (mk-entity! "a" "A") (mk-entity! "b" "B")
  (mk-rel! "b" "USES" "a")
  (let [ns (unwrap! (kg/neighbors *ctx* "a" {:direction :in}))]
    (is (= ["b"] (mapv :neighbor_id ns)))))

(deftest neighbors-filtered-by-predicate
  (mk-entity! "a" "A") (mk-entity! "b" "B") (mk-entity! "c" "C")
  (mk-rel! "a" "USES"       "b")
  (mk-rel! "a" "DEPENDS_ON" "c")
  (let [ns (unwrap! (kg/neighbors *ctx* "a" {:predicates ["USES"]}))]
    (is (= ["b"] (mapv :neighbor_id ns)))))

(deftest bfs-respects-depth
  (mk-entity! "a" "A") (mk-entity! "b" "B")
  (mk-entity! "c" "C") (mk-entity! "d" "D")
  (mk-rel! "a" "USES" "b")
  (mk-rel! "b" "USES" "c")
  (mk-rel! "c" "USES" "d")
  (let [r1 (unwrap! (kg/bfs *ctx* "a" {:max-depth 1}))
        r2 (unwrap! (kg/bfs *ctx* "a" {:max-depth 2}))
        r3 (unwrap! (kg/bfs *ctx* "a" {:max-depth 3}))]
    (is (= #{"a" "b"}         (set (map :node_id r1))))
    (is (= #{"a" "b" "c"}     (set (map :node_id r2))))
    (is (= #{"a" "b" "c" "d"} (set (map :node_id r3))))))

(deftest bfs-cycle-safe
  (mk-entity! "a" "A") (mk-entity! "b" "B")
  (mk-rel! "a" "USES" "b")
  (mk-rel! "b" "USES" "a")
  (let [r (unwrap! (kg/bfs *ctx* "a" {:max-depth 5}))]
    (is (= #{"a" "b"} (set (map :node_id r))))))

(deftest subgraph-returns-nodes-and-edges
  (mk-entity! "a" "A") (mk-entity! "b" "B") (mk-entity! "c" "C")
  (mk-rel! "rab" "a" "USES" "b")
  (mk-rel! "rbc" "b" "USES" "c")
  (let [sg (unwrap! (kg/subgraph *ctx* "a" {:max-depth 2}))]
    (is (= #{"a" "b" "c"}    (set (map :id (:nodes sg)))))
    (is (= #{"rab" "rbc"}    (set (map :id (:edges sg)))))))
