(ns loom.embedder
  "Text → 768-dim float vector via Ollama nomic-embed-text (local, free).
   Falls back to a zero vector if Ollama is unreachable."
  (:require [hato.client :as http]
            [cheshire.core :as json]
            [loom.envelope :refer [with-provenance]]))

(def ^:private default-model "nomic-embed-text")
(def ^:private vector-dim 768)

(defn- ollama-url [ctx]
  (get-in ctx [:config :ollama-url] "http://localhost:11434"))

(defn zero-vector
  "Return a 768-dim zero vector. Used as fallback when Ollama is unreachable."
  []
  (vec (repeat vector-dim 0.0)))

(defn- ollama-available? [url]
  (try
    (let [resp (http/get (str url "/api/tags")
                         {:connect-timeout 1000
                          :request-timeout 2000
                          :as :string})]
      (= 200 (:status resp)))
    (catch Exception _ false)))

(defonce ^:private warned? (atom false))

(defn embed
  "Convert text to a 768-dim float vector using the local Ollama model.
   If Ollama is unreachable, returns a zero vector and prints a one-time warning.
   Semantic search will still work — results will just be unranked.
   Returns an envelope {:ok? true :result [float ...]}."
  [ctx text]
  (with-provenance "loom.embedder/embed" 1
    (let [url (ollama-url ctx)]
      (if-not (ollama-available? url)
        (do
          (when-not @warned?
            (reset! warned? true)
            (println (str "[loom] WARNING: Ollama not reachable at " url
                          " — using zero vectors. Semantic search will not rank results."
                          " Start Ollama and run (reset! loom.embedder/warned? false) to re-enable.")))
          (zero-vector))
        (let [body (json/encode {:model  default-model
                                 :prompt (str text)})
              resp (http/post (str url "/api/embeddings")
                              {:content-type    :json
                               :body            body
                               :connect-timeout 5000
                               :request-timeout 10000
                               :as              :string})
              data (json/parse-string (:body resp) true)]
          (or (:embedding data)
              (throw (ex-info "Ollama returned no embedding"
                              {:response data :text text}))))))))
