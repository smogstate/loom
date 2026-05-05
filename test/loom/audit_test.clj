(ns loom.audit-test
  "Coverage for loom.audit: type-prefix validation, append-only writes,
   filter queries."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [loom.kg :as kg]
            [loom.audit :as audit]
            [loom.envelope :refer [unwrap!]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *ctx* nil)

(defn- temp-dir []
  (str (Files/createTempDirectory "loom-audit-test-" (into-array FileAttribute []))))

(defn audit-fixture [f]
  (let [ldir    (temp-dir)
        db-conn (kg/connect-file! ldir)]
    (kg/init-schema! db-conn)
    (kg/start-writer!)
    (binding [*ctx* {:db-conn db-conn :session-id "s1" :config {:loom-dir ldir}}]
      (try (f)
           (finally (.close db-conn))))))

(use-fixtures :each audit-fixture)

(deftest log-accepts-allowed-prefixes
  (doseq [t ["guard.denial" "guard.redaction" "system.warning"
             "agent.start" "agent.stop" "agent.failure"]]
    (is (string? (unwrap! (audit/log! *ctx* {:type t :content (str "x for " t)}))))))

(deftest log-rejects-other-types
  (doseq [t ["finding" "conclusion" "approval" "rejection" "failure"
             "guard"  "warning" ""]]
    (is (thrown-with-msg? Exception #"audit: invalid type"
          (unwrap! (audit/log! *ctx* {:type t :content "x"}))))))

(deftest log-rejects-nil-type
  (is (thrown-with-msg? Exception #"audit: invalid type"
        (unwrap! (audit/log! *ctx* {:content "x"})))))

(deftest query-newest-first
  (unwrap! (audit/log! *ctx* {:type "guard.denial"     :content "a"}))
  (Thread/sleep 5)
  (unwrap! (audit/log! *ctx* {:type "guard.redaction"  :content "b"}))
  (Thread/sleep 5)
  (unwrap! (audit/log! *ctx* {:type "system.warning"   :content "c"}))
  (let [rs (unwrap! (audit/query *ctx*))]
    (is (= ["c" "b" "a"] (mapv :content rs)))))

(deftest query-by-types
  (unwrap! (audit/log! *ctx* {:type "guard.denial"    :content "d1"}))
  (unwrap! (audit/log! *ctx* {:type "guard.redaction" :content "r1"}))
  (unwrap! (audit/log! *ctx* {:type "system.warning"  :content "w1"}))
  (let [rs (unwrap! (audit/query *ctx* {:types ["guard.denial"]}))]
    (is (= ["d1"] (mapv :content rs)))))

(deftest query-by-type-prefix
  (unwrap! (audit/log! *ctx* {:type "guard.denial"    :content "d"}))
  (unwrap! (audit/log! *ctx* {:type "guard.redaction" :content "r"}))
  (unwrap! (audit/log! *ctx* {:type "system.warning"  :content "w"}))
  (let [rs (unwrap! (audit/query *ctx* {:type-prefix "guard."}))]
    (is (= #{"d" "r"} (set (map :content rs))))))

(deftest query-by-agent-id
  (unwrap! (audit/log! *ctx* {:type "agent.start" :agent-id "alice" :content "a"}))
  (unwrap! (audit/log! *ctx* {:type "agent.start" :agent-id "bob"   :content "b"}))
  (let [rs (unwrap! (audit/query *ctx* {:agent-id "alice"}))]
    (is (= ["a"] (mapv :content rs)))))

(deftest query-respects-limit
  (dotimes [i 25]
    (unwrap! (audit/log! *ctx* {:type "system.warning" :content (str "w" i)})))
  (let [rs (unwrap! (audit/query *ctx* {:limit 10}))]
    (is (= 10 (count rs)))))
