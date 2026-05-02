(ns loom.budget
  "Resource-aware optimization: per-agent budgets, usage recording, reporting.
   All agent tool calls should go through loom.budget/call to be metered.
   Direct internal DB calls are not recorded — infrastructure, not agent actions."
  (:require [loom.db :as db]
            [loom.envelope :refer [with-provenance]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.core.async :as async]))

;; ---------------------------------------------------------------------------
;; Dynamic agent identity
;; ---------------------------------------------------------------------------

(def ^:dynamic *agent-id*
  "Bind to the current agent name before calling loom.budget/call.
   Example: (binding [loom.budget/*agent-id* \"analyzer\"] ...)"
  nil)

;; ---------------------------------------------------------------------------
;; Config loading (memoised with TTL)
;; ---------------------------------------------------------------------------

(def ^:private config-cache (atom {:ts 0 :cfg nil}))
(def ^:private config-ttl-ms (* 60 1000))   ; 1 minute

(def ^:private default-config
  {:default {:usd-per-call 0.0}
   :ops     {}
   :budgets {:default   {:usd 1.00 :duration-ms 60000 :calls 1000}
             "analyzer" {:usd 5.00 :duration-ms 300000 :calls 5000}
             "finder"   {:usd 1.00 :duration-ms 60000  :calls 2000}}})

(defn- load-config*
  "Internal: returns raw config map (no envelope)."
  [ctx]
  (let [now   (System/currentTimeMillis)
        cache @config-cache]
    (if (< (- now (:ts cache)) config-ttl-ms)
      (:cfg cache)
      (let [path (str (get-in ctx [:config :loom-dir]) "/budget.edn")
            cfg  (if (.exists (io/file path))
                   (merge-with merge default-config (edn/read-string (slurp path)))
                   default-config)]
        (reset! config-cache {:ts now :cfg cfg})
        cfg))))

(defn load-config
  "Read <loom-dir>/budget.edn (memoised w/ TTL). Returns config map."
  [ctx]
  (with-provenance "loom.budget/load-config" 1
    (load-config* ctx)))

(defn reload-config!
  "Force reload of budget.edn on next call to load-config."
  [_ctx]
  (reset! config-cache {:ts 0 :cfg nil})
  nil)

;; ---------------------------------------------------------------------------
;; Ring buffer for batched writes
;; ---------------------------------------------------------------------------

(def ^:private pending-rows (atom []))
(def ^:private first-pending-ts (atom nil))
(def ^:private batch-size 64)
(def ^:private flush-interval-ms 5000)

(defn- flush-pending! [ctx]
  (let [rows (first (reset-vals! pending-rows []))]
    (reset! first-pending-ts nil)
    (when (seq rows)
      (db/save-usage-batch! ctx rows))))

(defn- maybe-flush! [ctx]
  (let [rows @pending-rows
        ts   @first-pending-ts
        now  (System/currentTimeMillis)]
    (when (or (>= (count rows) batch-size)
              (and ts (>= (- now ts) flush-interval-ms)))
      (flush-pending! ctx))))

(defn- enqueue-row! [ctx row]
  (swap! pending-rows conj row)
  (swap! first-pending-ts #(or % (System/currentTimeMillis)))
  (maybe-flush! ctx))

;; ---------------------------------------------------------------------------
;; Recording
;; ---------------------------------------------------------------------------

(defn record!
  "Append a usage row derived from an envelope. Returns envelope unchanged.
   Batched via in-memory ring; flushed every 64 rows or 5s."
  [ctx envelope agent-id]
  (with-provenance "loom.budget/record!" 1
    (let [prov    (get envelope :provenance {})
          op      (get prov :op "unknown")
          cfg     (load-config* ctx)
          usd     (get-in cfg [:ops op :usd-per-call]
                           (get-in cfg [:default :usd-per-call] 0.0))
          row     {:id          (str (java.util.UUID/randomUUID))
                   :session_id  (:session-id ctx)
                   :agent_id    agent-id
                   :op          op
                   :version     (get prov :version 1)
                   :duration_ms (get prov :duration-ms 0)
                   :ok          (boolean (:ok? envelope))
                   :usd_cost    (double usd)
                   :tokens_in   nil
                   :tokens_out  nil}]
      (enqueue-row! ctx row)
      envelope)))

;; ---------------------------------------------------------------------------
;; Budget resolution & enforcement
;; ---------------------------------------------------------------------------

(defn budget-for
  "Resolve effective budget map for agent-id from budget.edn, merging :default.
   => {:usd 5.00 :duration-ms 300000 :calls 5000}"
  [ctx agent-id]
  (with-provenance "loom.budget/budget-for" 1
    (let [cfg      (load-config* ctx)
          defaults (get-in cfg [:budgets :default] {:usd 1.00 :duration-ms 60000 :calls 1000})
          agent-b  (when agent-id (get-in cfg [:budgets agent-id]))]
      (merge defaults agent-b))))

(defn current-usage
  "Sum {:usd :duration_ms :calls} for agent-id within the current session.
   Flushes the ring buffer first so counts are accurate.
   Returns {:usd 0.0 :duration_ms 0 :calls 0} if no rows yet."
  [ctx agent-id]
  (with-provenance "loom.budget/current-usage" 1
    (flush-pending! ctx)
    (or (loom.envelope/unwrap!
          (db/query-usage-scalar ctx
            "SELECT SUM(usd_cost)    AS usd,
                    SUM(duration_ms) AS duration_ms,
                    COUNT(*)         AS calls
             FROM usage
             WHERE session_id = ? AND agent_id = ?"
            (:session-id ctx) (or agent-id "")))
        {:usd 0.0 :duration_ms 0 :calls 0})))

(defn enforce!
  "Throw ex-info :budget-exceeded if current-usage >= budget-for.
   No-op if agent-id is nil. Returns nil on success."
  [ctx agent-id]
  (with-provenance "loom.budget/enforce!" 1
    (when agent-id
      ;; Flush pending so counts are up-to-date
      (flush-pending! ctx)
      (let [u (loom.envelope/unwrap!
                 (db/query-usage-scalar ctx
                   "SELECT SUM(usd_cost)    AS usd,
                           SUM(duration_ms) AS duration_ms,
                           COUNT(*)         AS calls
                    FROM usage
                    WHERE session_id = ? AND agent_id = ?"
                   (:session-id ctx) agent-id))
            cfg      (load-config* ctx)
            defaults (get-in cfg [:budgets :default] {:usd 1.00 :duration-ms 60000 :calls 1000})
            b        (merge defaults (get-in cfg [:budgets agent-id]))]
        (cond
          (>= (double (or (:usd u) 0.0)) (double (:usd b Double/MAX_VALUE)))
          (throw (ex-info "Budget exceeded: usd"
                          {:type  :budget-exceeded
                           :field :usd
                           :used  (:usd u)
                           :limit (:usd b)
                           :agent agent-id}))
          (>= (long (or (:duration_ms u) 0)) (long (:duration-ms b Long/MAX_VALUE)))
          (throw (ex-info "Budget exceeded: duration-ms"
                          {:type  :budget-exceeded
                           :field :duration-ms
                           :used  (:duration_ms u)
                           :limit (:duration-ms b)
                           :agent agent-id}))
          (>= (long (or (:calls u) 0)) (long (:calls b Long/MAX_VALUE)))
          (throw (ex-info "Budget exceeded: calls"
                          {:type  :budget-exceeded
                           :field :calls
                           :used  (:calls u)
                           :limit (:calls b)
                           :agent agent-id})))))))

;; ---------------------------------------------------------------------------
;; Entrypoint
;; ---------------------------------------------------------------------------

(defn call
  "Canonical agent entrypoint: enforce budget, run op-fn, record usage.
   args is an explicit seq (not varargs) — caller controls the shape.
   Binds *agent-id* in context automatically.

   Example:
     (binding [loom.budget/*agent-id* \"analyzer\"]
       (loom.budget/call ctx some-tool [arg1 arg2]))"
  [ctx op-fn args]
  (with-provenance "loom.budget/call" 1
    (enforce! ctx *agent-id*)
    (let [env (apply op-fn ctx args)]
      (record! ctx env *agent-id*)
      env)))

;; ---------------------------------------------------------------------------
;; Reporting
;; ---------------------------------------------------------------------------

(defn report
  "Aggregated usage report.
   opts: {:session-id <str> :agent-id <str> :since-ms <epoch-ms> :group-by #{:agent-id :op}}
   Returns vec of {:agent-id :op :calls :usd :duration-ms}."
  [ctx opts]
  (with-provenance "loom.budget/report" 1
    (flush-pending! ctx)
    (let [{:keys [session-id agent-id since-ms group-by]
           :or   {group-by #{:agent-id :op}}} opts
          group-cols (cond-> []
                       (contains? (set group-by) :agent-id) (conj "agent_id")
                       (contains? (set group-by) :op)       (conj "op"))
          group-cols (if (seq group-cols) group-cols ["agent_id" "op"])
          group-str  (clojure.string/join ", " group-cols)
          conditions (cond-> []
                       session-id (conj "session_id = ?")
                       agent-id   (conj "agent_id = ?")
                       since-ms   (conj "ts >= ?"))
          where-str  (if (seq conditions)
                       (str "WHERE " (clojure.string/join " AND " conditions))
                       "")
          params     (cond-> []
                       session-id (conj session-id)
                       agent-id   (conj agent-id)
                       since-ms   (conj (java.sql.Timestamp. (long since-ms))))
          sql        (str "SELECT " group-str
                          ", COUNT(*) AS calls"
                          ", SUM(usd_cost) AS usd"
                          ", SUM(duration_ms) AS duration_ms"
                          " FROM usage "
                          where-str
                          " GROUP BY " group-str
                          " ORDER BY usd DESC")]
      (loom.envelope/unwrap! (apply db/query-usage-raw ctx sql params)))))

;; ---------------------------------------------------------------------------
;; Periodic flush loop + init
;; ---------------------------------------------------------------------------

(defonce ^:private flush-loop-started? (atom false))

(defn init!
  "Load config, start periodic flush loop. Called once by loom.core/start!."
  [ctx]
  (load-config* ctx)
  (when (compare-and-set! flush-loop-started? false true)
    (async/go-loop []
      (async/<! (async/timeout flush-interval-ms))
      (try (flush-pending! ctx) (catch Exception _))
      (recur)))
  nil)
