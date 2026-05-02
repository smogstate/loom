(ns loom.metrics
  "Agent performance dashboard — DuckDB queries over events, goals, and usage parquet files."
  (:require [clojure.pprint :as pp]
            [loom.db :as db]
            [loom.envelope :refer [with-provenance]]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- q [ctx sql]
  (db/query-raw (:conn ctx) sql []))

(defn- pq [ctx k]
  (str "read_parquet('" (db/parquet-path (get-in ctx [:config :loom-dir]) k) "')"))

;; ---------------------------------------------------------------------------
;; Goal metrics
;; ---------------------------------------------------------------------------

(defn goal-completion-rate
  "Ratio of done goals to total non-open goals. Returns {:done :total :rate}."
  [ctx]
  (with-provenance "loom.metrics/goal-completion-rate" 1
    (first
      (q ctx (str "SELECT
                     COUNT(*) FILTER (WHERE status = 'done')  AS done,
                     COUNT(*) FILTER (WHERE status != 'open') AS total,
                     ROUND(100.0 *
                       COUNT(*) FILTER (WHERE status = 'done') /
                       NULLIF(COUNT(*) FILTER (WHERE status != 'open'), 0), 1) AS rate
                   FROM " (pq ctx :goals))))))

(defn avg-time-to-completion
  "Average/min/max ms between created_at and updated_at for done goals."
  [ctx]
  (with-provenance "loom.metrics/avg-time-to-completion" 1
    (first
      (q ctx (str "SELECT
                     ROUND(AVG(epoch_ms(updated_at) - epoch_ms(created_at))) AS avg_ms,
                     MIN(epoch_ms(updated_at) - epoch_ms(created_at))        AS min_ms,
                     MAX(epoch_ms(updated_at) - epoch_ms(created_at))        AS max_ms
                   FROM " (pq ctx :goals) "
                   WHERE status = 'done'")))))

;; ---------------------------------------------------------------------------
;; Event metrics
;; ---------------------------------------------------------------------------

(defn event-breakdown
  "Count of each event type per agent across all sessions."
  [ctx]
  (with-provenance "loom.metrics/event-breakdown" 1
    (q ctx (str "SELECT type, agent_id, COUNT(*) AS n
                 FROM " (pq ctx :events) "
                 GROUP BY type, agent_id
                 ORDER BY n DESC"))))

(defn failure-rate
  "Failure event ratio per agent."
  [ctx]
  (with-provenance "loom.metrics/failure-rate" 1
    (q ctx (str "SELECT
                   agent_id,
                   COUNT(*) FILTER (WHERE type = 'failure') AS failures,
                   COUNT(*)                                  AS total,
                   ROUND(100.0 *
                     COUNT(*) FILTER (WHERE type = 'failure') /
                     NULLIF(COUNT(*), 0), 1)                 AS failure_pct
                 FROM " (pq ctx :events) "
                 GROUP BY agent_id
                 ORDER BY failure_pct DESC"))))

(defn goals-with-failures
  "Goals that had at least one failure event — proxy for retry activity."
  [ctx]
  (with-provenance "loom.metrics/goals-with-failures" 1
    (first
      (q ctx (str "SELECT
                     COUNT(*) FILTER (WHERE type = 'failure')          AS total_failures,
                     COUNT(DISTINCT goal_id) FILTER (WHERE type = 'failure') AS goals_with_failures
                   FROM " (pq ctx :events))))))

;; ---------------------------------------------------------------------------
;; Usage / cost metrics
;; ---------------------------------------------------------------------------

(defn usage-summary
  "Total tokens, cost, call counts, and error rate per agent+op."
  [ctx]
  (with-provenance "loom.metrics/usage-summary" 1
    (q ctx (str "SELECT
                   agent_id,
                   op,
                   COUNT(*)                AS calls,
                   SUM(tokens_in)          AS tokens_in,
                   SUM(tokens_out)         AS tokens_out,
                   ROUND(SUM(usd_cost), 4) AS usd_total,
                   ROUND(AVG(duration_ms)) AS avg_ms,
                   COUNT(*) FILTER (WHERE ok = false) AS errors
                 FROM " (pq ctx :usage) "
                 GROUP BY agent_id, op
                 ORDER BY usd_total DESC"))))

(defn cost-per-goal
  "Average USD cost per completed goal (joins usage + goals on session_id)."
  [ctx]
  (with-provenance "loom.metrics/cost-per-goal" 1
    (first
      (q ctx (str "SELECT
                     ROUND(SUM(u.usd_cost) / NULLIF(COUNT(DISTINCT g.id), 0), 4) AS usd_per_goal
                   FROM " (pq ctx :usage) " u
                   JOIN " (pq ctx :goals) " g ON u.session_id = g.session_id
                   WHERE g.status = 'done'")))))

(defn slowest-ops
  "Top-10 slowest ops by average duration_ms."
  [ctx]
  (with-provenance "loom.metrics/slowest-ops" 1
    (q ctx (str "SELECT op, agent_id,
                   COUNT(*)                AS calls,
                   ROUND(AVG(duration_ms)) AS avg_ms,
                   MAX(duration_ms)        AS max_ms
                 FROM " (pq ctx :usage) "
                 GROUP BY op, agent_id
                 ORDER BY avg_ms DESC
                 LIMIT 10"))))

(defn search-call-counts
  "How many times each search op was called, per session."
  [ctx]
  (with-provenance "loom.metrics/search-call-counts" 1
    (q ctx (str "SELECT session_id, op, COUNT(*) AS calls
                 FROM " (pq ctx :usage) "
                 WHERE op LIKE '%search%'
                 GROUP BY session_id, op
                 ORDER BY calls DESC"))))

;; ---------------------------------------------------------------------------
;; Dashboard
;; ---------------------------------------------------------------------------

(defn dashboard
  "Print a full performance dashboard to stdout. Returns a map of all metrics."
  [ctx]
  (with-provenance "loom.metrics/dashboard" 1
    (let [metrics {:goal-completion    (goal-completion-rate ctx)
                   :time-to-completion (avg-time-to-completion ctx)
                   :event-breakdown    (event-breakdown ctx)
                   :failure-rate       (failure-rate ctx)
                   :goals-with-failures (goals-with-failures ctx)
                   :usage-summary      (usage-summary ctx)
                   :cost-per-goal      (cost-per-goal ctx)
                   :slowest-ops        (slowest-ops ctx)
                   :search-call-counts (search-call-counts ctx)}]
      (println "\n=== LOOM AGENT PERFORMANCE DASHBOARD ===\n")
      (doseq [[k v] metrics]
        (println (str "\n--- " (name k) " ---"))
        (pp/pprint v))
      (println "\n=========================================\n")
      metrics)))
