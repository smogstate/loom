(ns loom.math-test
  (:require [clojure.test :refer [deftest is testing]]
            [loom.envelope :refer [unwrap!]]
            [loom.seed.math :as math]))

(def ctx {}) ;; math tools don't use ctx

(deftest mean-test
  (is (= 3.0 (double (unwrap! (math/mean ctx [1 2 3 4 5])))))
  (is (= 5.0 (double (unwrap! (math/mean ctx [5]))))))

(deftest median-odd-test
  (is (= 3 (unwrap! (math/median ctx [1 2 3 4 5])))))

(deftest median-even-test
  (is (= 2.5 (unwrap! (math/median ctx [1 2 3 4])))))

(deftest stddev-test
  (let [sd (unwrap! (math/stddev ctx [2 4 4 4 5 5 7 9]))]
    (is (< (Math/abs (- sd 2.0)) 0.001))))

(deftest compound-interest-test
  (let [result (unwrap! (math/compound-interest ctx {:principal 1000 :rate 0.05 :years 10 :n 12}))]
    (is (> result 1647.0))
    (is (< result 1648.0))))

(deftest compound-interest-missing-args
  (is (false? (:ok? (math/compound-interest ctx {:principal 1000 :rate 0.05})))))

(deftest percentage-test
  (is (= 50.0 (unwrap! (math/percentage ctx 1 2)))))
