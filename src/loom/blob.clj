(ns loom.blob
  "Large payload pipeline: ingest to disk, semantic chunk via LLM, embed, search.
   Raw bytes never enter DuckDB or the LLM context directly."
  (:require [clojure.java.io :as io]
             [clojure.string :as str]
             [cheshire.core :as json]
             [loom.db :as db]
             [loom.graph :as graph]
             [loom.embedder :as embedder]
             [loom.envelope :refer [with-provenance unwrap!]])
  (:import [java.security MessageDigest]
           [java.util Base64]
           [java.io ByteArrayOutputStream]
           [java.util.zip GZIPOutputStream GZIPInputStream]))

;; ---------------------------------------------------------------------------
;; Utilities
;; ---------------------------------------------------------------------------

(defn- sha256 [^bytes data]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md data)
    (let [digest (.digest md)]
      (apply str (map #(format "%02x" %) digest)))))

(defn- date-path []
  (let [now (java.time.LocalDate/now)]
    (format "%d/%02d" (.getYear now) (.getMonthValue now))))

(defn- write-gzip! [path ^bytes data]
  (io/make-parents path)
  (with-open [out (GZIPOutputStream. (io/output-stream path))]
    (.write out data)))

(defn read-raw
  "Read and decompress a gzipped blob file. Returns String."
  [ctx blob]
  (let [path (:path blob)]
    (with-open [in  (GZIPInputStream. (io/input-stream path))
                out (ByteArrayOutputStream.)]
      (io/copy in out)
      (.toString out "UTF-8"))))

;; ---------------------------------------------------------------------------
;; Splitting — line-based (no LLM required)
;; LLM-assisted chunking can be layered on top by the caller if ctx :llm is set
;; ---------------------------------------------------------------------------

(def ^:private chunk-lines 150)

(defn- line-split
  "Split text into chunks of chunk-lines lines each."
  [text]
  (->> (str/split-lines text)
       (partition-all chunk-lines)
       (mapv #(str/join "\n" %))))

(defn- semantic-split
  "Split text into chunks. Uses ctx :llm if available for semantic splitting,
   otherwise falls back to line-based splitting."
  [ctx text _source]
  (if-let [llm-fn (:llm ctx)]
    (try
      (let [prompt (str "Split the following text into semantic chunks of ~500 tokens each.\n"
                        "Return a JSON array of chunk strings. No explanation, just the array.\n\n"
                        text)
            result (llm-fn {:prompt prompt :model :cheap})]
        (json/parse-string result true))
      (catch Exception _
        (line-split text)))
    (line-split text)))

(defn- summarize-batch
  "Summarize chunks. Uses ctx :llm if available, otherwise uses first line of each chunk."
  [ctx chunks]
  (if-let [llm-fn (:llm ctx)]
    (try
      (let [prompt (str "Summarize each chunk in one sentence. "
                        "Return a JSON array, same order as input.\n\n"
                        (json/encode chunks))
            result (llm-fn {:prompt prompt :model :cheap})]
        (json/parse-string result true))
      (catch Exception _
        (mapv #(first (str/split-lines %)) chunks)))
    (mapv #(first (str/split-lines %)) chunks)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn ingest!
  "Store raw payload (String or bytes) to disk, index metadata in DuckDB.
   Returns envelope with blob-id."
  [ctx payload {:keys [source agent-id]}]
  (with-provenance "loom.blob/ingest!" 1
    (let [data  (if (string? payload) (.getBytes payload "UTF-8") payload)
          id    (sha256 data)
          ldir  (get-in ctx [:config :loom-dir] ".loom")
          path  (str ldir "/blobs/" (date-path) "/" id ".gz")]
      (write-gzip! path data)
      (unwrap! (db/save-blob! ctx {:id         id
                                   :path       path
                                   :source     (or source "unknown")
                                   :agent-id   (or agent-id (get-in ctx [:agent :id]))
                                   :size-bytes (count data)}))
      id)))

(defn chunk!
  "Semantically chunk a blob, summarize each chunk with LLM (batched), embed and store.
   Resumes from last completed offset if interrupted."
  [ctx blob-id]
  (with-provenance "loom.blob/chunk!" 1
    (let [blob      (unwrap! (db/get-blob ctx blob-id))
          text      (read-raw ctx blob)
          chunks    (semantic-split ctx text (:source blob))
          batches   (partition-all 20 (map-indexed vector chunks))
          done-set  (unwrap! (db/completed-offsets ctx blob-id))]
      (doseq [batch batches]
        (let [new-batch (remove #(done-set (first %)) batch)]
          (when (seq new-batch)
            (let [raw-chunks (mapv second new-batch)
                  summaries  (try
                               (summarize-batch ctx raw-chunks)
                               (catch Exception _
                                 ;; fallback: use raw text as its own summary
                                 (db/log-event! ctx
                                   {:type      "warning"
                                    :content   (str "batch summarization failed for blob " blob-id
                                                    " — using raw text as summary")
                                    :session-id (:session-id ctx)})
                                 raw-chunks))]
              (doseq [[[i chunk] summary] (map vector new-batch summaries)]
                (let [vec (unwrap! (embedder/embed ctx summary))]
                  (unwrap! (db/save-chunk! ctx
                             {:blob-id blob-id
                              :offset  i
                              :vector  vec
                              :summary summary
                              :content chunk}))))))))
      blob-id)))

(defn index!
  "Convenience: ingest + chunk in one call. Returns blob-id."
  [ctx payload opts]
  (with-provenance "loom.blob/index!" 1
    (let [blob-id (unwrap! (ingest! ctx payload opts))]
      (chunk! ctx blob-id)
      (let [source     (or (:source opts) "unknown")
            entity-kind (if (re-find #"^https?://" source) "external" "file")
            source-entity-id (str "source/" (sha256 (.getBytes source "UTF-8")))]
        (unwrap! (graph/upsert-entity! ctx
                  {:id              blob-id
                   :canonical_name  source
                   :kind            entity-kind
                   :aliases         [source]
                   :attrs           {:blob_id blob-id}
                   :confidence      1.0
                   :source_count    1
                   :source_sessions [(:session-id ctx)]}
                  {:scope :global}))
        (unwrap! (graph/upsert-entity! ctx
                  {:id              source-entity-id
                   :canonical_name  source
                   :kind            entity-kind
                   :aliases         [source]
                   :attrs           {}
                   :confidence      1.0
                   :source_count    1
                   :source_sessions [(:session-id ctx)]}
                  {:scope :global}))
        (unwrap! (graph/upsert-relation! ctx
                  {:subject_id      blob-id
                   :predicate       "DEFINED_IN"
                   :object_id       source-entity-id
                   :confidence      1.0
                   :source_id       blob-id
                   :source_table    "blobs"
                   :source_sessions [(:session-id ctx)]}
                  {:scope :global}))
        (let [chunks (unwrap! (db/list-chunks-by-blob ctx blob-id))]
          (doseq [c chunks]
            (let [chunk-id (:id c)
                  summary  (or (:summary c) (str "chunk-" (:chunk_offset c)))
                  chunk-entity-id (str "chunk/" chunk-id)]
              (unwrap! (graph/upsert-entity! ctx
                        {:id              chunk-entity-id
                         :canonical_name  summary
                         :kind            "concept"
                         :aliases         [summary]
                         :attrs           {:chunk_id chunk-id
                                           :blob_id blob-id
                                           :chunk_offset (:chunk_offset c)}
                         :vector          (:vector c)
                         :confidence      1.0
                         :source_count    1
                         :source_sessions [(:session-id ctx)]}
                        {:scope :global}))
              (unwrap! (graph/upsert-relation! ctx
                        {:subject_id      chunk-entity-id
                         :predicate       "MENTIONED_IN"
                         :object_id       blob-id
                         :confidence      1.0
                         :source_id       chunk-id
                         :source_table    "chunks"
                         :source_sessions [(:session-id ctx)]}
                        {:scope :global}))))))
      blob-id)))
