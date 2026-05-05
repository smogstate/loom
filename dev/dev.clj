(ns dev
  "REPL convenience namespace. Starts loom and binds ctx."
  (:require [loom.core :as loom]
            [loom.kg :as kg]
            [loom.audit :as audit]
            [loom.tools :as tools]
            [loom.blob :as blob]
            [loom.embedder :as embedder]
            [loom.envelope :refer [unwrap!]]))

(defonce ctx (atom nil))

(defn start! []
  (reset! ctx (loom/start!))
  (println "[dev] loom started. Use @ctx to access the context.")
  @ctx)

(comment
  ;; Start loom
  (start!)

  ;; Search tools (semantic)
  (let [v (unwrap! (embedder/embed @ctx "compound interest"))]
    (unwrap! (kg/query-entities @ctx {:vector v :kind "tool" :limit 3})))

  ;; Search concepts by name
  (unwrap! (kg/query-entities @ctx {:name-prefix "User" :kind "concept"}))

  ;; Author a project-model entity
  (let [v (unwrap! (embedder/embed @ctx "Payment Flow"))]
    (unwrap! (kg/upsert-entity! @ctx
              {:id "concept/payment-flow"
               :kind "concept"
               :canonical_name "Payment Flow"
               :vector v})))

  ;; Audit log query (governance / tracing)
  (unwrap! (audit/query @ctx {:type-prefix "guard." :limit 50})))
