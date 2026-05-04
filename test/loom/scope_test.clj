(ns loom.scope-test
  (:require [clojure.test :refer [deftest is testing]]
            [loom.scope :as scope :refer [GLOBAL_SID normalize-stack default-stack]]))

(deftest global-sid-is-nil-uuid
  (is (= "00000000-0000-0000-0000-000000000000" GLOBAL_SID)))

(deftest normalize-appends-global
  (testing "single session sid auto-appends GLOBAL_SID"
    (is (= ["A" GLOBAL_SID] (normalize-stack ["A"]))))
  (testing "two-element stack appends GLOBAL_SID"
    (is (= ["A" "B" GLOBAL_SID] (normalize-stack ["A" "B"])))))

(deftest normalize-strict-mode-skips-global
  (testing "strict? true keeps stack as-is"
    (is (= ["A"] (normalize-stack ["A"] :strict? true)))
    (is (= [GLOBAL_SID] (normalize-stack [GLOBAL_SID] :strict? true))))
  (testing "strict? true with multi-element"
    (is (= ["A" "B"] (normalize-stack ["A" "B"] :strict? true)))))

(deftest normalize-dedups-preserving-first
  (testing "duplicate GLOBAL_SID collapses to one"
    (is (= [GLOBAL_SID] (normalize-stack [GLOBAL_SID GLOBAL_SID]))))
  (testing "explicit duplicates dedup"
    (is (= ["A" GLOBAL_SID] (normalize-stack ["A" GLOBAL_SID GLOBAL_SID]))))
  (testing "first occurrence wins"
    (is (= ["A" "B" GLOBAL_SID] (normalize-stack ["A" "B" "A" "B"])))))

(deftest normalize-drops-nil-and-empty
  (is (= ["A" GLOBAL_SID] (normalize-stack [nil "A" nil])))
  (is (= ["A" GLOBAL_SID] (normalize-stack ["" "A" ""])))
  (is (= [GLOBAL_SID] (normalize-stack [nil ""]))))

(deftest normalize-no-extra-append-when-global-already-present
  (is (= ["A" GLOBAL_SID] (normalize-stack ["A" GLOBAL_SID])))
  (is (= ["A" GLOBAL_SID "B"] (normalize-stack ["A" GLOBAL_SID "B"])))
  (testing "global mid-stack still suppresses auto-append"
    (let [result (normalize-stack ["A" GLOBAL_SID "B"])]
      (is (= 1 (count (filter #(= GLOBAL_SID %) result)))))))

(deftest normalize-empty-input-throws
  (is (thrown? clojure.lang.ExceptionInfo (normalize-stack [] :strict? true)))
  (is (thrown? clojure.lang.ExceptionInfo (normalize-stack nil :strict? true)))
  (is (thrown? clojure.lang.ExceptionInfo (normalize-stack [nil ""] :strict? true))))

(deftest normalize-empty-non-strict-still-yields-global
  (testing "non-strict empty input collapses to [GLOBAL_SID] (not an error)"
    (is (= [GLOBAL_SID] (normalize-stack [])))
    (is (= [GLOBAL_SID] (normalize-stack nil)))))

(deftest default-stack-uses-ctx-session-id
  (testing "ctx with session-id"
    (is (= ["S1" GLOBAL_SID] (default-stack {:session-id "S1"}))))
  (testing "ctx with nil session-id collapses to global only"
    (is (= [GLOBAL_SID] (default-stack {:session-id nil}))))
  (testing "ctx without :session-id key"
    (is (= [GLOBAL_SID] (default-stack {})))))
