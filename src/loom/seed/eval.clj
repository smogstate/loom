(ns loom.seed.eval
  "Seed tool: evaluate Clojure expressions at runtime."
  (:require [loom.envelope :refer [with-provenance]]))

(defn ^{:doc "Evaluate a Clojure expression string in the running nREPL context. Returns the result as a string."
        :tags ["eval" "execute" "code" "clojure"]}
  eval-expr
  [ctx code-str]
  (with-provenance "eval-expr" 1
    (str (eval (read-string code-str)))))
