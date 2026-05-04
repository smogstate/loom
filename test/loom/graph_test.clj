(ns loom.graph-test
  "Public graph API coverage for the :session-ids vector contract.
   Verifies stack priority, strict-mode, and GLOBAL_SID dedup at the wrapper layer."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [loom.db :as db]
            [loom.graph :as graph]
            [loom.scope :as scope :refer [GLOBAL_SID]]
            [loom.envelope :refer [unwrap!]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *ctx* nil)

(defn- temp-dir []
  (str (Files/createTempDirectory "loom-graph-test-" (into-array FileAttribute []))))

(defn- v [x] (vec (repeat 768 x)))

(defn graph-fixture [f]
  (let [ldir (temp-dir)
        conn (db/connect!)
        _    (db/start-writer!)
        ctx  {:conn       conn
              :session-id "sA"
              :config     {:loom-dir ldir}}]
    (binding [*ctx* ctx]
      (f))))

(use-fixtures :each graph-fixture)

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- mk-entity!
  "Upsert an entity into a specific scope via the graph wrapper."
  [id name scope sid]
  (unwrap! (graph/upsert-entity! *ctx*
            {:id id :canonical_name name :kind "concept" :vector (v 0.1)
             :confidence 0.9 :source_count 1
             :source_sessions (if sid [sid] [])}
            (cond-> {:scope scope}
              sid (assoc :session-id sid)))))

(defn- mk-rel!
  [sid-subj sid-obj scope sid]
  (unwrap! (graph/upsert-relation! *ctx*
            {:subject_id sid-subj :predicate "RELATES_TO" :object_id sid-obj
             :confidence 1.0 :source_id (str sid-subj "->" sid-obj)
             :source_table "facts"
             :source_sessions (if sid [sid] [])}
            (cond-> {:scope scope}
              sid (assoc :session-id sid)))))

;; ---------------------------------------------------------------------------
;; stack priority — same id in two scopes; lower-rank wins
;; ---------------------------------------------------------------------------

(deftest search-entities-by-name-stack-priority
  (testing "session entry shadows global entry of same id"
    (mk-entity! "shared" "GLOBAL"  :global  nil)
    (mk-entity! "shared" "SESS-A"  :session "sA")
    (mk-entity! "shared" "SESS-B"  :session "sB")
    (let [hits-a (unwrap! (graph/search-entities-by-name *ctx* "" :session-ids ["sA"] :limit 50))
          hits-b (unwrap! (graph/search-entities-by-name *ctx* "" :session-ids ["sB"] :limit 50))
          names-a (set (map :canonical_name hits-a))
          names-b (set (map :canonical_name hits-b))]
      ;; Each search returns the highest-priority row per id (the session row),
      ;; not the global one — even though global is also in the stack.
      (is (contains? names-a "SESS-A")
          (str "expected SESS-A in stack-A view, got " names-a))
      (is (contains? names-b "SESS-B")
          (str "expected SESS-B in stack-B view, got " names-b))
      (is (not (contains? names-a "SESS-B"))
          "stack-A view must not see session-B's private row")
      (is (not (contains? names-b "SESS-A"))
          "stack-B view must not see session-A's private row"))))

;; ---------------------------------------------------------------------------
;; strict mode — global tail is excluded
;; ---------------------------------------------------------------------------

(deftest search-strict-mode-excludes-global
  (mk-entity! "g1" "GLOBAL-ONLY" :global nil)
  (mk-entity! "s1" "SESSION-ONLY" :session "sA")
  (let [non-strict (unwrap! (graph/search-entities-by-name *ctx* "" :session-ids ["sA"] :limit 50))
        strict     (unwrap! (graph/search-entities-by-name *ctx* "" :session-ids ["sA"]
                                                            :strict? true :limit 50))
        names-ns   (set (map :canonical_name non-strict))
        names-s    (set (map :canonical_name strict))]
    (is (contains? names-ns "GLOBAL-ONLY")
        "non-strict default-stack must include global")
    (is (contains? names-ns "SESSION-ONLY"))
    (is (contains? names-s "SESSION-ONLY"))
    (is (not (contains? names-s "GLOBAL-ONLY"))
        "strict mode must exclude global tail")))

;; ---------------------------------------------------------------------------
;; GLOBAL_SID dedup — passing GLOBAL_SID explicitly does not duplicate global
;; ---------------------------------------------------------------------------

(deftest stack-dedups-global-sid
  (mk-entity! "g1" "G" :global nil)
  (mk-entity! "s1" "S" :session "sA")
  (let [explicit (unwrap! (graph/search-entities-by-name *ctx* ""
                            :session-ids ["sA" GLOBAL_SID] :limit 50))
        implicit (unwrap! (graph/search-entities-by-name *ctx* ""
                            :session-ids ["sA"] :limit 50))]
    ;; Both call patterns must yield the same set of rows.
    (is (= (set (map :id explicit))
           (set (map :id implicit)))
        "explicit GLOBAL_SID in stack must dedup against the auto-appended tail")))

;; ---------------------------------------------------------------------------
;; relations & neighbors — :session-ids contract on relation reads
;; ---------------------------------------------------------------------------

(deftest search-relations-respects-stack
  (mk-entity! "a" "A" :global nil)
  (mk-entity! "b" "B" :global nil)
  (mk-rel! "a" "b" :session "sA")
  (let [in-stack  (unwrap! (graph/search-relations *ctx* :session-ids ["sA"] :subject-id "a"))
        off-stack (unwrap! (graph/search-relations *ctx* :session-ids ["sB"]
                                                   :strict? true :subject-id "a"))]
    (is (= 1 (count in-stack)) "session-A relation visible to stack-A reader")
    (is (= 0 (count off-stack)) "session-A relation invisible under strict stack-B")))

(deftest neighbors-respects-stack
  (mk-entity! "a" "A" :global nil)
  (mk-entity! "b" "B" :global nil)
  (mk-rel! "a" "b" :session "sA")
  (let [n  (unwrap! (graph/neighbors *ctx* "a" :session-ids ["sA"] :direction :out))
        n0 (unwrap! (graph/neighbors *ctx* "a" :session-ids ["sB"] :strict? true :direction :out))]
    (is (= 1 (count n)))
    (is (= 0 (count n0)))))

;; ---------------------------------------------------------------------------
;; default-stack fallback when :session-ids is omitted
;; ---------------------------------------------------------------------------

(deftest default-stack-from-ctx
  (mk-entity! "g1" "G-DEFAULT" :global nil)
  (mk-entity! "s1" "S-DEFAULT" :session "sA")
  (let [hits (unwrap! (graph/search-entities-by-name *ctx* "" :limit 50))
        names (set (map :canonical_name hits))]
    ;; ctx :session-id is "sA" — default-stack should be ["sA" GLOBAL_SID]
    (is (contains? names "G-DEFAULT"))
    (is (contains? names "S-DEFAULT"))))
