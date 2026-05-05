(ns loom.tools
  "Tool registry: register, scan namespaces, rollback, search.
   The runtime mirror in loom.state is kept in sync with DuckDB."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [loom.state :as state]
            [loom.kg :as kg]
            [loom.embedder :as embedder]
            [loom.envelope :refer [with-provenance unwrap!]]))

(defn- vec->sql-literal [v]
  (when (and v (seq v))
    (str "[" (str/join ", " (map float v)) "]::FLOAT[768]")))

(defn register!
  "Register a var as a tool: embed its doc+tags, store in DuckDB and state mirror.
   var-sym must be a fully-qualified symbol, e.g. 'loom.seed.math/compound-interest.

   All persistent writes — tool row, three KG entities (tool/agent/concept),
   two relations — execute inside ONE `kg/write!` thunk so that the API-layer
   endpoint check in `kg/upsert-relation*` sees its referent entities (which
   were inserted earlier in the same thunk) and the whole compound write is
   atomic. Entity ids are deterministic (`tool/<name>`, `agent/<id>`,
   `concept/<name>`) so re-registration is idempotent without fuzzy resolve."
  [ctx var-sym]
  (with-provenance "loom.tools/register!" 1
    (let [v    (resolve var-sym)
          _    (when-not v (throw (ex-info "Cannot resolve var" {:sym var-sym})))
          m    (meta v)
          name (str (:ns m) "/" (:name m))
          doc  (or (:doc m) "")
          tags (or (:tags m) [])
          text (str/join " " [name doc (str/join " " tags)])
          vec  (unwrap! (embedder/embed ctx text))
          tool {:name name :doc doc :tags tags :vector vec
                :code (str v) :fn v :version 1}
          agent-id        (or (get-in ctx [:agent :id]) "system")
          tool-id         (str "tool/" name)
          agent-entity-id (str "agent/" agent-id)
          concept-name    (last (str/split name #"/"))
          concept-id      (str "concept/" concept-name)
          tags-json       (json/encode tags)
          vec-frag        (vec->sql-literal vec)]
      (state/add-tool! tool)
      (kg/write!
        (fn []
          (let [conn (:db-conn ctx)]
            ;; tool row — DELETE+INSERT pattern (FLOAT[768] update unsupported)
            (kg/exec! conn "DELETE FROM tools WHERE id = ?" tool-id)
            (kg/exec! conn
                      (str "INSERT INTO tools
                              (id, name, doc, tags, vector, code, version)
                              VALUES (?, ?, ?, ?, " vec-frag ", ?, 1)")
                      tool-id name doc tags-json (str v))
            ;; KG entities — must precede their relations (endpoint check).
            (kg/upsert-entity* ctx
              {:id tool-id
               :kind "tool"
               :canonical_name name
               :aliases [name]
               :attrs {:doc doc :tags tags :code (str v)}
               :vector vec})
            (kg/upsert-entity* ctx
              {:id agent-entity-id
               :kind "agent"
               :canonical_name agent-id
               :aliases [agent-id]
               :attrs {}})
            (kg/upsert-entity* ctx
              {:id concept-id
               :kind "concept"
               :canonical_name concept-name
               :aliases [concept-name]
               :attrs {:from_tool name}
               :vector vec})
            ;; relations — both endpoints exist by now in this thunk.
            (kg/upsert-relation* ctx
              {:id (str "rel/" tool-id "/AUTHORED_BY/" agent-entity-id)
               :subject_id tool-id
               :predicate "AUTHORED_BY"
               :object_id agent-entity-id
               :attrs {:source_table "tools"}})
            (kg/upsert-relation* ctx
              {:id (str "rel/" tool-id "/IMPLEMENTS/" concept-id)
               :subject_id tool-id
               :predicate "IMPLEMENTS"
               :object_id concept-id
               :attrs {:source_table "tools"}}))))
      name)))

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

;; rollback! and start-watcher! removed in v2.
;;   - rollback!: register! uses deterministic ids + DELETE+INSERT, so prior
;;     versions are not retained. Re-introduce when DuckDB supports UPDATE
;;     on FLOAT[768] (and we re-add a versioning shape to the tools table).
;;   - start-watcher!: there is no longer a "side door" for putting tools
;;     into the runtime mirror without persisting. `register!` is the only
;;     sanctioned path; it writes both the runtime mirror and the DB.
