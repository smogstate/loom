(ns loom.state
  "Shared atoms: session-state holds agents, history, and the runtime tool mirror.")

(defonce session-state
  (atom {:agents  {}    ;; per-agent context maps
         :history []    ;; all agent events this session (flushed to Parquet on exit)
         :tools   {}})) ;; name → {:doc :tags :vector :fn} — runtime mirror of DuckDB tools table

(defn add-tool!
  "Add or replace a tool in the runtime mirror."
  [tool]
  (swap! session-state assoc-in [:tools (:name tool)] tool))

(defn get-tools [] (:tools @session-state))

(defn add-event!
  "Append an event to the in-memory history."
  [event]
  (swap! session-state update :history conj event))

(defn set-agent!
  "Store per-agent context."
  [agent-id ctx-map]
  (swap! session-state assoc-in [:agents agent-id] ctx-map))

(defn get-agent [agent-id]
  (get-in @session-state [:agents agent-id]))
