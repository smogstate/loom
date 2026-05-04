(ns loom.seed.project
  "LOOM.md ingestion: parse, embed, store as stable facts tagged by project."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [loom.memory :as memory]
            [loom.graph :as graph]
            [loom.envelope :refer [with-provenance unwrap!]]))

(defn- content-hash [s]
  (str (hash s)))

(defn- parse-chunks
  "Split markdown into logical chunks: one per bullet, one per paragraph block."
  [text]
  (let [lines   (str/split-lines text)
        chunks  (atom [])
        current (atom [])]
    (doseq [line lines]
      (cond
        ;; bullet point — flush current, treat as its own chunk
        (re-matches #"^\s*[-*]\s+.+" line)
        (do
          (when (seq @current)
            (swap! chunks conj (str/join "\n" @current))
            (reset! current []))
          (swap! chunks conj (str/trim line)))

        ;; blank line — flush current paragraph
        (str/blank? line)
        (do
          (when (seq @current)
            (swap! chunks conj (str/join "\n" @current))
            (reset! current [])))

        :else
        (swap! current conj line)))
    (when (seq @current)
      (swap! chunks conj (str/join "\n" @current)))
    (filterv (complement str/blank?) @chunks)))

(defn ingest-project-md!
  "Parse LOOM.md, embed each chunk, store as :stable facts tagged by project.
   Idempotent — skips chunks already stored (by content hash)."
  {:doc "Ingest LOOM.md into the vector DB as stable project facts. Idempotent."
   :tags ["project" "loom.md" "memory" "ingest"]}
  [ctx path {:keys [project]}]
  (with-provenance "loom.seed.project/ingest-project-md!" 1
    (when-not (.exists (io/file path))
      (throw (ex-info "LOOM.md not found" {:path path})))
    (let [text   (slurp path)
          chunks (parse-chunks text)
          stored (atom 0)]
      (doseq [chunk chunks]
        (let [h    (content-hash chunk)
              hits (unwrap! (graph/search-entities ctx chunk 1 :kind :concept))]
          (when-not (some #(= (:canonical_name %) chunk) hits)
            (unwrap! (memory/promote! ctx chunk
                       {:type    :stable
                        :tags    [project "loom.md"]
                        :append-to-loom-md false}))
            (swap! stored inc))))
      {:ingested @stored :total (count chunks)})))
