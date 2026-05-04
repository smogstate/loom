(ns loom.tools
  "Tool registry: register, scan namespaces, rollback, search.
   The runtime mirror in loom.state is kept in sync with DuckDB."
  (:require [clojure.string :as str]
            [loom.state :as state]
            [loom.db :as db]
            [loom.graph :as graph]
            [loom.embedder :as embedder]
            [loom.envelope :refer [with-provenance unwrap!]]))

(defn register!
  "Register a var as a tool: embed its doc+tags, store in DuckDB and state mirror.
   var-sym must be a fully-qualified symbol, e.g. 'loom.seed.math/compound-interest."
  [ctx var-sym]
  (with-provenance "loom.tools/register!" 1
    (let [v    (resolve var-sym)
          _    (when-not v (throw (ex-info "Cannot resolve var" {:sym var-sym})))
          m    (meta v)
          name (str (:ns m) "/" (:name m))
          doc  (or (:doc m) "")
          tags (or (:tags m) [])
          text (str/join " " [name doc (str/join " " tags)])
          vec  (unwrap! (embedder/embed ctx text))]
      (let [tool {:name      name
                  :doc       doc
                  :tags      tags
                  :vector    vec
                  :code      (str v)
                  :fn        v
                  :version   1}
            agent-id (or (get-in ctx [:agent :id]) "system")]
        (state/add-tool! tool)
        (let [tool-id (unwrap! (db/save-tool! ctx tool))
              agent-entity-id (str "agent/" agent-id)
              concept-name (last (str/split name #"/"))]
          (unwrap! (graph/upsert-entity! ctx
                    {:id              tool-id
                     :canonical_name  name
                     :kind            "tool"
                     :aliases         [name]
                     :attrs           {:doc doc :tags tags :code (str v)}
                     :vector          vec
                     :confidence      1.0
                     :source_count    1
                     :source_sessions [(:session-id ctx)]}
                    {:scope :global}))
          (unwrap! (graph/upsert-entity! ctx
                    {:id              agent-entity-id
                     :canonical_name  agent-id
                     :kind            "agent"
                     :aliases         [agent-id]
                     :attrs           {}
                     :confidence      1.0
                     :source_count    1
                     :source_sessions [(:session-id ctx)]}
                    {:scope :global}))
          (unwrap! (graph/upsert-entity! ctx
                    {:canonical_name  concept-name
                     :kind            "concept"
                     :aliases         [concept-name]
                     :attrs           {:from_tool name}
                     :vector          vec
                     :confidence      1.0
                     :source_count    1
                     :source_sessions [(:session-id ctx)]}
                    {:scope :global}))
          (let [concept-id (:resolved-id (unwrap! (graph/resolve-entity! ctx
                                                  {:canonical_name concept-name
                                                   :kind :concept
                                                   :aliases [concept-name]
                                                   :attrs {:from_tool name}
                                                   :vector vec}
                                                  {:scope :global})))]
            (unwrap! (graph/upsert-relation! ctx
                      {:subject_id      tool-id
                       :predicate       "AUTHORED_BY"
                       :object_id       agent-entity-id
                       :confidence      1.0
                       :source_id       tool-id
                       :source_table    "tools"
                       :source_sessions [(:session-id ctx)]}
                      {:scope :global}))
            (unwrap! (graph/upsert-relation! ctx
                      {:subject_id      tool-id
                       :predicate       "IMPLEMENTS"
                       :object_id       concept-id
                       :confidence      1.0
                       :source_id       tool-id
                       :source_table    "tools"
                       :source_sessions [(:session-id ctx)]}
                      {:scope :global}))))
        name))))

(defn scan-ns!
  "Register all public vars with :doc metadata in the given namespace."
  [ctx ns-sym]
  (with-provenance "loom.tools/scan-ns!" 1
    (require ns-sym)
    (let [registered (atom [])]
      (doseq [[_ v] (ns-publics ns-sym)]
        (when (:doc (meta v))
          (let [sym (symbol (str (:ns (meta v))) (str (:name (meta v))))]
            (register! ctx sym)
            (swap! registered conj sym))))
      @registered)))

(defn rollback!
  "Roll back a tool to a previous version by id."
  [ctx tool-name previous-id]
  (with-provenance "loom.tools/rollback!" 1
    (unwrap! (db/rollback-tool! ctx tool-name previous-id))))

;; ---------------------------------------------------------------------------
;; Watcher — auto-persist new tools added to state at runtime
;; ---------------------------------------------------------------------------

(defn start-watcher!
  "Add a watch on session-state that persists newly registered tools to DuckDB.
   NOTE: tools observed here have already been wrapped by loom.guard/wrap-tool
   inside loom.state/add-tool!, so db/save-tool! will persist the guarded :fn.
   Do not unwrap."
  [ctx]
  (add-watch state/session-state :tool-vectorizer
    (fn [_ _ old new]
      (let [added (clojure.set/difference
                    (set (keys (:tools new)))
                    (set (keys (:tools old))))]
        (doseq [tool-name added]
          (let [tool (get-in new [:tools tool-name])]
            (when (and tool (:vector tool))
              (db/save-tool! ctx tool))))))))
