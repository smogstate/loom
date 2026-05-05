(ns loom.tools-test
  "Coverage for loom.tools/register! v2: single-thunk compound writes,
   tool row + KG (tool/agent/concept entities + AUTHORED_BY/IMPLEMENTS
   relations), deterministic ids, idempotent re-registration."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [loom.db :as db]
            [loom.kg :as kg]
            [loom.tools :as tools]
            [loom.embedder :as embedder]
            [loom.envelope :refer [unwrap!]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *ctx* nil)

(defn- temp-dir []
  (str (Files/createTempDirectory "loom-tools-test-" (into-array FileAttribute []))))

(defn- v [x] (vec (repeat 768 (float x))))

(defn tools-fixture [f]
  (let [ldir    (temp-dir)
        db-conn (kg/connect-file! ldir)]
    (kg/init-schema! db-conn)
    (kg/start-writer!)
    (binding [*ctx* {:db-conn db-conn :session-id "sess-1" :config {:loom-dir ldir}}]
      (with-redefs [embedder/embed
                    (fn [_ctx _text]
                      {:ok? true :result (v 0.1) :provenance {} :error nil})]
        (try (f)
             (finally (.close db-conn)))))))

(use-fixtures :each tools-fixture)

;; ---------------------------------------------------------------------------
;; A throwaway var to register
;; ---------------------------------------------------------------------------

(defn ^{:doc "demo tool for register tests" :tags ["demo"]}
  demo-tool [_ctx x] (inc x))

;; ---------------------------------------------------------------------------
;; tests
;; ---------------------------------------------------------------------------

(deftest register-writes-tool-row
  (unwrap! (tools/register! *ctx* `demo-tool))
  (let [conn (:db-conn *ctx*)
        rows (kg/query conn "SELECT id, name, doc, version FROM tools")]
    (is (= 1 (count rows)))
    (is (= "tool/loom.tools-test/demo-tool" (:id (first rows))))
    (is (= "loom.tools-test/demo-tool"      (:name (first rows))))
    (is (= 1 (int (:version (first rows)))))))

(deftest register-writes-three-kg-entities
  (unwrap! (tools/register! *ctx* `demo-tool))
  (let [tool-id    "tool/loom.tools-test/demo-tool"
        concept-id "concept/demo-tool"
        agent-id   "agent/system"
        tool-e (unwrap! (kg/query-entities *ctx* {:ids [tool-id]}))
        cncpt-e (unwrap! (kg/query-entities *ctx* {:ids [concept-id]}))
        agent-e (unwrap! (kg/query-entities *ctx* {:ids [agent-id]}))]
    (is (= 1 (count tool-e)))
    (is (= "tool" (:kind (first tool-e))))
    (is (= 1 (count cncpt-e)))
    (is (= "concept" (:kind (first cncpt-e))))
    (is (= 1 (count agent-e)))
    (is (= "agent" (:kind (first agent-e))))))

(deftest register-writes-authored-by-and-implements
  (unwrap! (tools/register! *ctx* `demo-tool))
  (let [tool-id "tool/loom.tools-test/demo-tool"
        rs (unwrap! (kg/query-relations *ctx* {:subject-id tool-id}))
        preds (set (map :predicate rs))]
    (is (contains? preds "AUTHORED_BY"))
    (is (contains? preds "IMPLEMENTS"))))

(deftest register-is-idempotent
  (unwrap! (tools/register! *ctx* `demo-tool))
  (unwrap! (tools/register! *ctx* `demo-tool))
  (let [conn (:db-conn *ctx*)
        n-tools (-> (kg/query conn "SELECT count(*) AS c FROM tools") first :c)
        n-ents  (count (unwrap! (kg/query-entities *ctx* {:kind "tool"})))]
    (is (= 1 (int n-tools))
        "second register! should overwrite, not duplicate, the tool row")
    (is (= 1 n-ents)
        "second register! should overwrite, not duplicate, the tool entity")))

(deftest register-uses-single-write-thunk
  (let [calls (atom 0)
        orig  kg/write!]
    (with-redefs [kg/write! (fn [f] (swap! calls inc) (orig f))]
      (unwrap! (tools/register! *ctx* `demo-tool)))
    (is (= 1 @calls)
        "register! must enqueue exactly one item on the write! channel")))
