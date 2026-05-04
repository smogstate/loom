(ns dev
  "REPL convenience namespace. Starts loom and binds ctx."
  (:require [loom.core :as loom]
            [loom.db :as db]
            [loom.graph :as graph]
            [loom.embedder :as embedder]
            [loom.tools :as tools]
            [loom.session :as session]
            [loom.memory :as memory]
            [loom.blob :as blob]
            [loom.envelope :refer [unwrap!]]))

(defonce ctx (atom nil))

(defn start! []
  (reset! ctx (loom/start!))
  (println "[dev] loom started. Use @ctx to access the context.")
  @ctx)

(comment
  ;; Start loom
  (start!)

  ;; Search tools
  (db/search-tools @ctx (unwrap! (embedder/embed @ctx "compound interest")) 3)

  ;; Run a task
  (router/route! @ctx "list all math tools")

  ;; Log a session fact
  (session/log-fact! @ctx "service runs on port 8080")

  ;; Promote a fact
  (memory/promote! @ctx "service runs on port 8080"
    {:type :stable :tags ["service" "config"]})
  )
