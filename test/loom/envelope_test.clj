(ns loom.envelope-test
  (:require [clojure.test :refer [deftest is]]
            [loom.envelope :refer [with-provenance ok? unwrap!]]))

(deftest envelope-success
  (let [result (with-provenance "test/op" 1 (+ 1 2))]
    (is (true? (:ok? result)))
    (is (= 3 (:result result)))
    (is (nil? (:error result)))
    (is (= "test/op" (get-in result [:provenance :op])))
    (is (= 1 (get-in result [:provenance :version])))
    (is (number? (get-in result [:provenance :duration-ms])))))

(deftest envelope-failure
  (let [result (with-provenance "test/fail"
                 (throw (Exception. "boom")))]
    (is (false? (:ok? result)))
    (is (nil? (:result result)))
    (is (= "boom" (get-in result [:error :message])))))

(deftest envelope-variadic-body
  (let [result (with-provenance "test/multi"
                 (let [x 1
                       y 2]
                   (+ x y)))]
    (is (true? (:ok? result)))
    (is (= 3 (:result result)))))

(deftest envelope-default-version
  (let [result (with-provenance "test/default" :ignored)]
    (is (= 1 (get-in result [:provenance :version])))))

(deftest unwrap-success
  (is (= 42 (unwrap! (with-provenance "test" 42)))))

(deftest unwrap-failure
  (is (thrown? clojure.lang.ExceptionInfo
               (unwrap! (with-provenance "test" (throw (Exception. "err")))))))

(deftest ok?-test
  (is (ok? (with-provenance "test" :ok)))
  (is (not (ok? (with-provenance "test" (throw (Exception. "x")))))))
