(ns loom.session
  "Per-session fact logging (tier-2 memory) stored in session parquet."
  (:require [loom.db :as db]
            [loom.graph :as graph]
            [loom.embedder :as embedder]
            [loom.envelope :refer [with-provenance unwrap!]]))

(defn log-fact!
  "Log a working observation to the session facts parquet."
  [ctx content]
  (with-provenance "loom.session/log-fact!" 1
    (let [vec (unwrap! (embedder/embed ctx content))]
      (unwrap! (db/save-session-fact! ctx
                 {:content  content
                  :vector   vec
                  :agent-id (get-in ctx [:agent :id])}))
      (let [resolved (unwrap! (graph/resolve-entity! ctx
                              {:canonical_name content
                               :kind :concept
                               :aliases [content]
                               :attrs {:agent_id (get-in ctx [:agent :id])}
                               :vector vec}
                              {:scope :session :session-id (:session-id ctx)}))]
        (:resolved-id resolved)))))

(defn search-facts
  "Semantic search within current session facts only."
  [ctx query k]
  (with-provenance "loom.session/search-facts" 1
    (unwrap! (graph/search-entities ctx query k
                                    :session-id (:session-id ctx)
                                    :kind :concept))))
