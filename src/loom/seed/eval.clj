(ns loom.seed.eval
  "Seed tool: evaluate Clojure expressions at runtime."
  (:require [loom.envelope :refer [with-provenance]]
            [loom.guard :as guard]))

(defn ^{:doc "Evaluate a Clojure expression string in the running nREPL context. Returns the result as a string."
        :tags ["eval" "execute" "code" "clojure"]}
  eval-expr
  [ctx code-str]
  (with-provenance "eval-expr" 1
    (let [{:keys [allow? reason detail]}
          (guard/check-eval-ast @guard/policy code-str)]
      (when-not allow?
        (guard/log-denial! ctx (or (:agent-id ctx) "unknown")
                           "loom.seed.eval/eval-expr" reason detail)
        (throw (ex-info "guard: eval denied"
                        {:reason reason :detail detail}))))
    (str (eval (read-string code-str)))))
