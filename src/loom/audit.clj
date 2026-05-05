(ns loom.audit
  "Append-only governance + tracing log.

   Per plans/architecture.md §2.1: audit accepts ONLY types matching
     `^(guard|system|agent)\\.`
   This is a deliberate narrowing from the legacy events table:
     - guard.*   policy enforcement (denials, redactions)
     - system.*  operational warnings (e.g. blob ingest hiccups)
     - agent.*   multi-agent run tracing (start, stop, failure)
   Subagent outputs (findings, conclusions, approvals, rejections) are
   returned via tool responses, NEVER persisted here.

   Validation is enforced at this API layer (not as a DB CHECK constraint)
   for clearer error messages."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [loom.kg :as kg]
            [loom.envelope :refer [with-provenance]]))

(def ^:private valid-type-re #"^(guard|system|agent)\..+")

(defn- validate-type! [type]
  (when-not (and (string? type) (re-matches valid-type-re type))
    (throw (ex-info "audit: invalid type"
                    {:reason :invalid-type
                     :type   type
                     :allowed-prefixes ["guard." "system." "agent."]}))))

(defn- uuid [] (str (java.util.UUID/randomUUID)))

(defn log!
  "Append a row to the audit log. Returns the new id.
     entry ::= {:type        -- required, ^(guard|system|agent)\\.…
                :content?    -- free text
                :agent-id?   -- string
                :session-id? -- string (audit-only metadata)
                :provenance? -- map (JSON-encoded)}"
  [ctx entry]
  (with-provenance "loom.audit/log!" 1
    (validate-type! (:type entry))
    (kg/write!
      (fn []
        (let [conn (:db-conn ctx)
              id   (uuid)
              prov-json (json/encode (or (:provenance entry) {}))]
          (kg/exec! conn
                    "INSERT INTO audit (id, session_id, agent_id, type, content, provenance)
                     VALUES (?, ?, ?, ?, ?, ?)"
                    id
                    (or (:session-id entry) (:session-id ctx))
                    (or (:agent-id entry)
                        (get-in ctx [:agent :id]))
                    (:type entry)
                    (:content entry)
                    prov-json)
          id)))))

(defn query
  "Read recent audit rows. All filters optional.
     opts ::= {:types?     [type ...]   ; e.g. [\"guard.denial\" \"guard.redaction\"]
               :type-prefix?  string    ; e.g. \"guard.\" — matches any guard.*
               :agent-id?  string
               :since?     inst-ms      ; epoch millis lower bound on ts
               :limit?     int          ; default 100, max 10000}.
   Results newest-first."
  ([ctx] (query ctx {}))
  ([ctx {:keys [types type-prefix agent-id since limit]
         :or   {limit 100}}]
   (with-provenance "loom.audit/query" 1
     (let [conn   (:db-conn ctx)
           wheres (atom [])
           params (atom [])]
       (when (seq types)
         (swap! wheres conj
                (str "type IN (" (str/join "," (repeat (count types) "?")) ")"))
         (swap! params into types))
       (when type-prefix
         (swap! wheres conj "type LIKE ? || '%'")
         (swap! params conj type-prefix))
       (when agent-id
         (swap! wheres conj "agent_id = ?")
         (swap! params conj agent-id))
       (when since
         (swap! wheres conj "ts >= ?")
         (swap! params conj (java.sql.Timestamp. (long since))))
       (let [where-sql (if (seq @wheres)
                         (str " WHERE " (str/join " AND " @wheres))
                         "")
             sql (str "SELECT id, ts, session_id, agent_id, type, content, provenance
                       FROM audit"
                      where-sql
                      " ORDER BY ts DESC LIMIT " (min (int limit) 10000))]
         (->> (apply kg/query conn sql @params)
              (mapv (fn [r]
                      (cond-> r
                        (:provenance r) (update :provenance
                                                #(when % (try (json/parse-string (str %) true)
                                                              (catch Exception _ %))))))) ))))))
