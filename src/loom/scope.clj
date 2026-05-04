(ns loom.scope
  "Session-stack scope normalisation.

   Loom reads take an ordered vector of session ids (`:session-ids`).
   First element wins on ties; the last element is `GLOBAL_SID` unless
   the caller opts into strict mode.

   Migration note: replaces the legacy scalar `:session-id` opt + `:scope`
   discriminator. There is no backward compatibility — see plan
   `plan/session-stack.md`.")

(def GLOBAL_SID
  "Reserved nil-UUID addressing the global parquets."
  "00000000-0000-0000-0000-000000000000")

(defn global-sid?
  "True when sid is the global sentinel."
  [sid]
  (= sid GLOBAL_SID))

(defn normalize-stack
  "Canonicalise a session-ids vector.
   - drops nil/empty entries
   - dedups while preserving first occurrence
   - appends GLOBAL_SID at the end unless strict? is true
   - returns a non-empty vector or throws ex-info

   Single source of truth for stack normalisation; every public read fn
   that accepts `:session-ids` should call this once at entry and forward
   the already-normalised vector to internal db-* fns."
  [session-ids & {:keys [strict?] :or {strict? false}}]
  (let [seen (volatile! #{})
        kept (reduce (fn [acc sid]
                       (if (or (nil? sid) (= "" sid) (contains? @seen sid))
                         acc
                         (do (vswap! seen conj sid) (conj acc sid))))
                     [] (or session-ids []))
        with-global (if (or strict? (some #{GLOBAL_SID} kept))
                      kept
                      (conj kept GLOBAL_SID))]
    (when (empty? with-global)
      (throw (ex-info "empty session stack"
                      {:input session-ids :strict? strict?})))
    with-global))

(defn default-stack
  "Standard read scope for an agent: `[(:session-id ctx) GLOBAL_SID]`.
   When `(:session-id ctx)` is nil the result collapses to `[GLOBAL_SID]`."
  [ctx]
  (normalize-stack [(:session-id ctx)]))
