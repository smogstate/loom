(ns loom.envelope
  "Provenance envelope — every tool call returns this shape.
   {:ok? true|false :result <value> :provenance {...} :error nil|<error-map>}")

(defn now-ms [] (System/currentTimeMillis))

(defmacro with-provenance
  "Wrap body forms in a provenance envelope. op is a string naming the operation.
   Second arg may optionally be an integer version (defaults to 1).
   All remaining forms are treated as the body (implicitly wrapped in do).

   Usage:
     (with-provenance \"loom.db/search-tools\"
       (step-1)
       (step-2))

     (with-provenance \"loom.db/search-tools\" 3
       (step-1)
       (step-2))
   "
  [op & args]
  (let [[version body-forms] (if (and (integer? (first args)) (> (count args) 1))
                               [(first args) (rest args)]
                               [1 args])]
    `(let [started# (now-ms)]
       (try
         (let [result# (do ~@body-forms)]
           {:ok?        true
            :result     result#
            :provenance {:op            ~op
                         :started-at-ms started#
                         :duration-ms   (- (now-ms) started#)
                         :source-tool   ~op
                         :version       ~version}
            :error      nil})
         (catch Exception e#
           {:ok?        false
            :result     nil
            :provenance {:op            ~op
                         :started-at-ms started#
                         :duration-ms   (- (now-ms) started#)
                         :source-tool   ~op
                         :version       ~version}
            :error      {:message (.getMessage e#)
                         :type    (str (class e#))}})))))

(defn ok? [envelope] (true? (:ok? envelope)))

(defn unwrap!
  "Extract :result or throw with the :error message."
  [envelope]
  (if (:ok? envelope)
    (:result envelope)
    (throw (ex-info (get-in envelope [:error :message] "tool error")
                    {:envelope envelope}))))

(defn with-tokens
  "Attach token counts to an existing envelope's :provenance map.
   Returns the updated envelope. Use after with-provenance when token
   counts are known (e.g. from an LLM API response).

   Example:
     (-> (with-provenance \"my-op\" (call-llm ...))
         (with-tokens {:tokens-in 120 :tokens-out 45}))"
  [envelope {:keys [tokens-in tokens-out]}]
  (cond-> envelope
    tokens-in  (assoc-in [:provenance :tokens-in]  tokens-in)
    tokens-out (assoc-in [:provenance :tokens-out] tokens-out)))
