(ns loom.state
  "Shared atoms: session-state holds agents, history, and the runtime tool mirror."
  (:require [loom.guard :as guard]))

(defonce session-state
  (atom {:agents  {}    ;; per-agent context maps
         :history []    ;; all agent events this session (flushed to Parquet on exit)
         :tools   {}})) ;; name → {:doc :tags :vector :fn} — runtime mirror of DuckDB tools table

(defn add-tool!
  "Add or replace a tool in the runtime mirror.
   Single chokepoint: every tool — whether registered through loom.tools/register!
   or written directly — passes through loom.guard/wrap-tool here.
   No :raw-fn slot is kept, so there is no public path back to the unguarded fn."
  [tool]
  (let [wrapped (guard/wrap-tool tool)]
    (swap! session-state assoc-in [:tools (:name wrapped)] wrapped)))

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
