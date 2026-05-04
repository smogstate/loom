(ns loom.goals
  "Hierarchical goal tracking — create, update, link events, query progress.
   All public fns return envelopes (with-provenance)."
  (:require [loom.db :as db]
            [loom.graph :as graph]
            [loom.embedder :as embedder]
            [loom.envelope :refer [with-provenance unwrap!]]))

(defn create-goal!
  "Create a goal. opts: :title :description :success-criteria :parent-id :status.
   Embeds (title + description) for future semantic search.
   Returns the new goal id."
  [ctx {:keys [title description success-criteria parent-id status] :as _opts}]
  (with-provenance "loom.goals/create-goal!" 1
    (let [text (str title "\n" description)
          vec  (unwrap! (embedder/embed ctx text))
          goal-id (unwrap! (db/save-goal! ctx
                     {:title            title
                      :description      description
                      :success-criteria success-criteria
                      :parent-id        parent-id
                      :status           (or status "open")
                      :session-id       (:session-id ctx)
                      :vector           vec}))]
      (unwrap! (graph/upsert-entity! ctx
                {:id              goal-id
                 :canonical_name  title
                 :kind            "goal"
                 :aliases         [title]
                 :attrs           {:description description
                                   :success_criteria success-criteria
                                   :status (or status "open")}
                 :vector          vec
                 :confidence      1.0
                 :source_count    1
                 :source_sessions [(:session-id ctx)]}
                {:scope :global}))
      (let [about (unwrap! (graph/resolve-entity! ctx
                           {:canonical_name title
                            :kind :concept
                            :aliases [title]
                            :attrs {:from_goal goal-id}
                            :vector vec}
                           {:scope :global}))]
        (unwrap! (graph/upsert-relation! ctx
                  {:subject_id      goal-id
                   :predicate       "ABOUT"
                   :object_id       (:resolved-id about)
                   :confidence      1.0
                   :source_id       goal-id
                   :source_table    "goals"
                   :source_sessions [(:session-id ctx)]}
                  {:scope :global})))
      (when parent-id
        (unwrap! (graph/upsert-relation! ctx
                  {:subject_id      goal-id
                   :predicate       "DEPENDS_ON"
                   :object_id       parent-id
                   :confidence      1.0
                   :source_id       goal-id
                   :source_table    "goals"
                   :source_sessions [(:session-id ctx)]}
                  {:scope :global})))
      goal-id)))

(defn update-status!
  "Transition a goal to a new status. Valid: open|active|blocked|done|abandoned.
   Returns the goal id."
  [ctx goal-id status]
  (with-provenance "loom.goals/update-status!" 1
    (let [updated-id (unwrap! (db/update-goal-status! ctx goal-id status))]
      (when (= status "done")
        (let [rels (unwrap! (graph/bfs ctx goal-id
                                      :session-id (:session-id ctx)
                                      :max-depth 4
                                      :limit 500
                                      :undirected? false))]
          (doseq [row rels]
            (when-not (= (:node_id row) goal-id)
              (unwrap! (graph/promote-entity! ctx (:node_id row)
                                              :session-id (:session-id ctx)
                                              :cascade? true))))))
      updated-id)))

(defn link-event!
  "Attach an existing event to a goal by writing events.goal_id.
   Returns the event id."
  [ctx event-id goal-id]
  (with-provenance "loom.goals/link-event!" 1
    (unwrap! (db/link-event-to-goal! ctx event-id goal-id))))

(defn open-goals
  "List goals with status in #{open active blocked} for the current session
   (or all sessions when :scope :global is passed).
   Returns a seq of goal maps."
  [ctx & {:keys [scope] :or {scope :session}}]
  (with-provenance "loom.goals/open-goals" 1
    (unwrap! (db/list-goals ctx {:scope      scope
                                 :session-id (:session-id ctx)
                                 :statuses   ["open" "active" "blocked"]}))))

(defn active
  "Return the single active goal for this session, or nil.
   Convenience fn for orchestrator Step 0."
  [ctx]
  (with-provenance "loom.goals/active" 1
    (->> (unwrap! (open-goals ctx))
         (filter #(= "active" (:status %)))
         first)))

(defn progress
  "Return a summary map for a goal:
     {:goal g :children [...] :events-count n :latest-event e}
   Walks parent_id tree one level for children."
  [ctx goal-id]
  (with-provenance "loom.goals/progress" 1
    (let [g        (unwrap! (db/get-goal ctx goal-id))
          children (unwrap! (db/list-goal-children ctx goal-id))
          events   (unwrap! (db/list-goal-events ctx goal-id))]
      {:goal         g
       :children     children
       :events-count (count events)
       :latest-event (last events)})))
