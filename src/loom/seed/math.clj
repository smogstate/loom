(ns loom.seed.math
  "Basic math and finance tools."
  (:require [loom.envelope :refer [with-provenance]]))

(defn mean
  "Arithmetic mean of a collection of numbers."
  {:doc "Calculate arithmetic mean of a numeric collection."
   :tags ["math" "stats" "mean"]}
  [ctx nums]
  (with-provenance "loom.seed.math/mean" 1
    (/ (reduce + nums) (count nums))))

(defn median
  "Median of a collection of numbers."
  {:doc "Calculate median of a numeric collection."
   :tags ["math" "stats" "median"]}
  [ctx nums]
  (with-provenance "loom.seed.math/median" 1
    (let [sorted (sort nums)
          n      (count sorted)
          mid    (quot n 2)]
      (if (odd? n)
        (nth sorted mid)
        (/ (+ (nth sorted (dec mid)) (nth sorted mid)) 2.0)))))

(defn stddev
  "Standard deviation of a collection of numbers."
  {:doc "Calculate standard deviation of a numeric collection."
   :tags ["math" "stats" "stddev"]}
  [ctx nums]
  (with-provenance "loom.seed.math/stddev" 1
    (let [m    (/ (reduce + nums) (count nums))
          vars (map #(Math/pow (- % m) 2) nums)]
      (Math/sqrt (/ (reduce + vars) (count nums))))))

(defn compound-interest
  "Calculate compound interest. Returns final amount."
  {:doc "Compound interest: principal × (1 + rate/n)^(n×years). Returns final amount."
   :tags ["math" "finance" "compound-interest"]}
  [ctx {:keys [principal rate years n]
        :or   {n 12}}]
  (with-provenance "loom.seed.math/compound-interest" 1
    (when (or (nil? principal) (nil? rate) (nil? years))
      (throw (ex-info "principal, rate, and years are required" {})))
    (* principal (Math/pow (+ 1 (/ rate n)) (* n years)))))

(defn percentage
  "Calculate what percent value is of total."
  {:doc "Calculate percentage: (value / total) × 100."
   :tags ["math" "percentage"]}
  [ctx value total]
  (with-provenance "loom.seed.math/percentage" 1
    (* 100.0 (/ value total))))
