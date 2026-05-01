(ns loom.seed.data
  "Data parsing and transformation tools."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [loom.envelope :refer [with-provenance]]))

(defn parse-json
  "Parse a JSON string into Clojure data."
  {:doc "Parse JSON string to Clojure map/vector."
   :tags ["data" "json" "parse"]}
  [ctx s]
  (with-provenance "loom.seed.data/parse-json" 1
    (json/parse-string s true)))

(defn parse-csv
  "Parse a CSV string into a vector of maps using the first row as headers."
  {:doc "Parse CSV text. First row is headers. Returns vector of maps."
   :tags ["data" "csv" "parse"]}
  [ctx text]
  (with-provenance "loom.seed.data/parse-csv" 1
    (let [lines   (str/split-lines text)
          headers (mapv str/trim (str/split (first lines) #","))]
      (mapv (fn [line]
              (zipmap headers (mapv str/trim (str/split line #","))))
            (rest lines)))))

(defn flatten-map
  "Flatten a nested map to a single level with dotted keys."
  {:doc "Flatten nested map. E.g. {:a {:b 1}} → {:a.b 1}."
   :tags ["data" "map" "flatten"]}
  [ctx m]
  (with-provenance "loom.seed.data/flatten-map" 1
    (letfn [(flatten-step [prefix m]
              (reduce-kv (fn [acc k v]
                           (let [key (if prefix (str prefix "." (name k)) (name k))]
                             (if (map? v)
                               (merge acc (flatten-step key v))
                               (assoc acc key v))))
                         {} m))]
      (flatten-step nil m))))
