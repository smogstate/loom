(ns loom.guard
  "Guardrails middleware: policy enforcement, rate limiting, output redaction,
   and denial/redaction audit logging for all agent-invoked tools.

   Integration points:
     1. loom.state/add-tool! calls wrap-tool — single chokepoint for all tools.
     2. loom.seed.eval/eval-expr calls check-eval-ast inline — defense-in-depth.
     3. Agents may call guard/call explicitly for per-agent attribution.

   Policy lives in .loom/guard.edn, loaded by init! and hot-reloadable via
   reload-policy!. Missing file → open-by-default (empty policy, no denials)."
  (:require [clojure.edn      :as edn]
            [clojure.java.io  :as io]
            [clojure.string   :as str]
            [clojure.walk     :as walk]
            [loom.envelope    :refer [with-provenance]]
            [loom.db          :as db]))

;; ---------------------------------------------------------------------------
;; Policy + rate-limit state
;; ---------------------------------------------------------------------------

(defonce policy  (atom {}))
(defonce buckets (atom {}))   ; { [agent-id tool-name] -> PersistentQueue<inst-ms> }

;; ---------------------------------------------------------------------------
;; Policy load / reload
;; ---------------------------------------------------------------------------

(defn- policy-path [ctx]
  (str (get-in ctx [:config :loom-dir] ".loom") "/guard.edn"))

(defn- compile-policy
  "Post-process raw EDN policy: compile :redact :pattern strings to regexes."
  [raw]
  (update raw :redact
          (fn [rules]
            (mapv (fn [r]
                    (update r :pattern #(when % (re-pattern %))))
                  (or rules [])))))

(defn init!
  "Load .loom/guard.edn into guard/policy. Missing file → open-by-default {}."
  [ctx]
  (let [f (io/file (policy-path ctx))]
    (reset! policy
            (compile-policy
              (if (.exists f)
                (edn/read-string (slurp f))
                {}))))
  nil)

(defn reload-policy!
  "Hot-reload policy from disk without restarting."
  [ctx]
  (init! ctx))

;; ---------------------------------------------------------------------------
;; Policy class dispatch
;; ---------------------------------------------------------------------------

(defn- policy-class [tool-name]
  (cond
    (= tool-name "loom.seed.eval/eval-expr")       :eval
    (str/starts-with? tool-name "loom.seed.http/") :url
    (str/starts-with? tool-name "loom.seed.fs/")   :fs
    :else                                           :default))

;; ---------------------------------------------------------------------------
;; Input checkers
;; ---------------------------------------------------------------------------

(defn check-eval-ast
  "Pure AST check for eval-expr. Returns {:allow? bool :reason kw :detail any}.
   Called both from wrap-tool and inline in loom.seed.eval/eval-expr."
  [policy code-str]
  (let [ns-allowlist  (get-in policy [:eval :ns-allowlist] #{})
        ns-denylist   (get-in policy [:eval :ns-denylist]  #{})
        sym-denylist  (get-in policy [:eval :sym-denylist] #{})
        ;; open-by-default: if no allowlist configured, allow everything
        open?         (empty? ns-allowlist)]
    (try
      (let [form (read-string code-str)]
        ;; Collect all symbols via postwalk
        (let [syms (atom [])]
          (walk/postwalk
            (fn [x] (when (symbol? x) (swap! syms conj x)) x)
            form)
          ;; Check each symbol
          (let [violation
                (some (fn [sym]
                        (let [ns-part (namespace sym)
                              nm-part (name sym)]
                          (cond
                            ;; bare symbol in denylist
                            (and (nil? ns-part)
                                 (contains? sym-denylist (symbol nm-part)))
                            {:reason :sym-denied :detail sym}

                            ;; namespaced symbol: check full sym in denylist (e.g. System/exit)
                            (and ns-part
                                 (contains? sym-denylist sym))
                            {:reason :sym-denied :detail sym}

                            ;; namespaced symbol: check ns-denylist
                            (and ns-part
                                 (contains? ns-denylist ns-part))
                            {:reason :ns-denied :detail sym}

                            ;; namespaced symbol: check ns-allowlist (if configured)
                            (and ns-part
                                 (not open?)
                                 (not (contains? ns-allowlist ns-part)))
                            {:reason :ns-not-allowed :detail sym}

                            :else nil)))
                      @syms)]
            (if violation
              (assoc violation :allow? false)
              ;; Anti-redef check: reject top-level def/defn/defmacro/defonce/in-ns/ns
              (let [top (when (seq? form) (first form))]
                (if (and top (contains? #{'def 'defn 'defmacro 'defonce 'in-ns 'ns} top))
                  {:allow? false :reason :redef-denied :detail top}
                  {:allow? true :reason nil :detail nil}))))))
      (catch Exception e
        {:allow? false :reason :reader-error :detail (ex-message e)}))))

(defn- check-url [policy url]
  (let [scheme-allowlist (get-in policy [:url :scheme-allowlist] #{"https" "http"})
        host-denylist    (get-in policy [:url :host-denylist]    #{})]
    (try
      (let [uri    (java.net.URI. url)
            scheme (.getScheme uri)
            host   (.getHost uri)]
        (cond
          (not (contains? scheme-allowlist scheme))
          {:allow? false :reason :scheme-denied :detail scheme}

          (contains? host-denylist host)
          {:allow? false :reason :host-denied :detail host}

          :else {:allow? true :reason nil :detail nil}))
      (catch Exception e
        {:allow? false :reason :url-parse-error :detail (ex-message e)}))))

(defn- check-fs-path [policy path]
  (let [path-allowlist (get-in policy [:fs :path-allowlist] [])
        path-denylist  (get-in policy [:fs :path-denylist]  [])
        open?          (empty? path-allowlist)]
    (cond
      (some #(str/starts-with? path %) path-denylist)
      {:allow? false :reason :path-denied :detail path}

      (and (not open?)
           (not (some #(str/starts-with? path %) path-allowlist)))
      {:allow? false :reason :path-not-allowed :detail path}

      :else {:allow? true :reason nil :detail nil})))

(defn- check-size [policy args]
  (let [max-bytes (get-in policy [:limits :max-input-bytes] Integer/MAX_VALUE)
        size      (count (pr-str args))]
    (if (> size max-bytes)
      {:allow? false :reason :input-too-large :detail {:size size :max max-bytes}}
      {:allow? true :reason nil :detail nil})))

;; ---------------------------------------------------------------------------
;; check-input multimethod
;; ---------------------------------------------------------------------------

(defmulti ^:private check-input
  (fn [_policy tool-name _args] (policy-class tool-name)))

(defmethod check-input :eval [policy _ args]
  (check-eval-ast policy (second args)))   ; args = [ctx code-str]

(defmethod check-input :url [policy _ args]
  (check-url policy (second args)))        ; args = [ctx url ...]

(defmethod check-input :fs [policy _ args]
  (check-fs-path policy (second args)))    ; args = [ctx path ...]

(defmethod check-input :default [policy _ args]
  (check-size policy args))

;; ---------------------------------------------------------------------------
;; Rate limiter — sliding window log
;; ---------------------------------------------------------------------------

(defn allow?
  "Sliding-window rate limiter. Returns true and records the call if within
   limits; false otherwise. now is System/currentTimeMillis.
   If no limit is configured for the tool, always returns true."
  [policy agent-id tool-name now]
  (let [limit (or (get-in policy [:rate-limits tool-name])
                  (get-in policy [:rate-limits :default]))]
    (if-not limit
      true   ; no limit configured → open-by-default
      (let [key         [agent-id tool-name]
            cutoff-min  (- now 60000)
            cutoff-hour (- now 3600000)]
        (loop []
          (let [old-b    @buckets
                log      (get old-b key clojure.lang.PersistentQueue/EMPTY)
                log'     (into clojure.lang.PersistentQueue/EMPTY
                               (filter #(>= % cutoff-hour) log))
                per-min  (count (filter #(>= % cutoff-min) log'))
                per-hour (count log')]
            (if (and (< per-min  (:per-minute limit))
                     (< per-hour (:per-hour   limit)))
              (let [new-b (assoc old-b key (conj log' now))]
                (if (compare-and-set! buckets old-b new-b)
                  true
                  (recur)))
              false)))))))

;; ---------------------------------------------------------------------------
;; Output redaction
;; ---------------------------------------------------------------------------

(defn- log-redaction!
  "Fire-and-forget: log a redaction event to events.parquet."
  [ctx agent-id tool-name pattern-name]
  (try
    (db/log-event! ctx {:type       "guard.redaction"
                        :agent-id   agent-id
                        :session-id (:session-id ctx)
                        :content    (pr-str {:tool         tool-name
                                             :pattern-name pattern-name})})
    (catch Exception _ nil)))

(defn redact
  "Walk value v, replacing regex matches with «REDACTED:name» tokens.
   Depth-limited to 1024 nodes to avoid pathological inputs."
  ([policy v] (redact policy v nil nil))
  ([policy v ctx agent-id]
   (let [rules      (get policy :redact [])
         node-count (atom 0)]
     (walk/postwalk
       (fn [x]
         (when (> (swap! node-count inc) 1024)
           (throw (ex-info "guard: redact depth limit exceeded" {:limit 1024})))
         (if (string? x)
           (reduce (fn [s {:keys [name pattern]}]
                     (let [s' (str/replace s pattern (str "«REDACTED:" name "»"))]
                       (when (and (not= s s') ctx)
                         (log-redaction! ctx agent-id "unknown" name))
                       s'))
                   x rules)
           x))
       v))))

;; ---------------------------------------------------------------------------
;; Denial logging
;; ---------------------------------------------------------------------------

(defn log-denial!
  "Write a guard.denial event to events.parquet."
  [ctx agent-id tool-name reason detail]
  (try
    (db/log-event! ctx {:type       "guard.denial"
                        :agent-id   agent-id
                        :session-id (:session-id ctx)
                        :content    (pr-str {:tool   tool-name
                                             :reason reason
                                             :detail detail})})
    (catch Exception _ nil)))

;; ---------------------------------------------------------------------------
;; wrap-tool — replaces :fn with a guarded fn, no :raw-fn exposed
;; ---------------------------------------------------------------------------

(defn wrap-tool
  "Return tool with :fn replaced by a guarded wrapper.
   No :raw-fn slot is kept — there is no public path back to the unguarded fn."
  [tool]
  (let [raw (:fn tool)
        nm  (:name tool)]
    (if-not (fn? raw)
      tool   ; no :fn (e.g. tool record from DB only) — pass through
      (assoc tool :fn
        (fn guarded [& args]
          (let [ctx      (first args)
                agent-id (or (:agent-id ctx) "unknown")
                now      (System/currentTimeMillis)
                pol      @policy]
            ;; 1. Input check
            (let [{:keys [allow? reason detail]} (check-input pol nm args)]
              (when-not allow?
                (log-denial! ctx agent-id nm reason detail)
                (throw (ex-info "guard: input denied"
                                {:tool nm :reason reason :detail detail}))))
            ;; 2. Rate limit
            (when-not (allow? pol agent-id nm now)
              (log-denial! ctx agent-id nm :rate-limit nil)
              (throw (ex-info "guard: rate limit exceeded"
                              {:tool nm :agent-id agent-id})))
            ;; 3. Call real fn + redact output
            (redact pol (apply raw args) ctx agent-id)))))))

;; ---------------------------------------------------------------------------
;; guard/call — explicit per-agent entrypoint
;; ---------------------------------------------------------------------------

(defn call
  "Call a registered tool by name with explicit agent attribution.
   Looks up the (already-wrapped) :fn from loom.state."
  [ctx agent-id tool-name args]
  (with-provenance "loom.guard/call" 1
    (let [tools (loom.state/get-tools)
          tool  (get tools tool-name)]
      (when-not tool
        (throw (ex-info "guard/call: unknown tool" {:tool tool-name})))
      (apply (:fn tool) (cons (assoc ctx :agent-id agent-id) args)))))

;; ---------------------------------------------------------------------------
;; search-denials — audit query, bypasses vector search
;; ---------------------------------------------------------------------------

(defn search-denials
  "Return recent guard.denial and guard.redaction events from events.parquet.
   Filters by SQL — no embedding required. Results newest-first.

   opts: {:agent-id str :tool str :since inst-ms :limit int}"
  ([ctx]            (search-denials ctx {}))
  ([ctx {:keys [agent-id tool limit since]
         :or   {limit 100}}]
   (with-provenance "loom.guard/search-denials" 1
     (let [conn   (db/connection ctx)
           path   (db/events-path ctx)
           wheres (cond-> ["type IN ('guard.denial', 'guard.redaction')"]
                    agent-id (conj "agent_id = ?")
                    since    (conj "ts >= ?")
                    tool     (conj "content LIKE ?"))
           params (cond-> []
                    agent-id (conj agent-id)
                    since    (conj since)
                    tool     (conj (str "%" tool "%")))
           sql    (str "SELECT id, ts, agent_id, type, content "
                       "FROM read_parquet('" path "') "
                       "WHERE " (str/join " AND " wheres) " "
                       "ORDER BY ts DESC LIMIT " (long limit))]
       (db/query-raw conn sql params)))))
