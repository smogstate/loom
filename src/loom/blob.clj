(ns loom.blob
  "Large payload pipeline: ingest to disk, semantic chunk via LLM, embed, search.
   Raw bytes never enter DuckDB or the LLM context directly."
  (:require [clojure.java.io :as io]
             [clojure.string :as str]
             [cheshire.core :as json]
             [loom.kg :as kg]
             [loom.audit :as audit]
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
;; Public API — v2 file-backed conn (.loom/loom.db)
;;
;; Storage migration only (cutover step 3b).  KG `file`/`chunk` entity
;; creation that previously lived in `index!` is deferred to the future
;; `loom.ingest` namespace; users who want the KG side now should call
;; `kg/upsert-entity!` directly.  Trigger semantics (when/who/how often
;; ingestion runs) are unchanged.
;; ---------------------------------------------------------------------------

(defn- vec->sql-literal [v]
  (when (and v (seq v))
    (str "[" (str/join ", " (map float v)) "]::FLOAT[768]")))

(defn- get-blob-row
  "Fetch a single blob row from the file-backed conn. Returns map or nil."
  [conn blob-id]
  (first (kg/query conn
                   "SELECT id, path, source, size_bytes, ts FROM blobs WHERE id = ?"
                   blob-id)))

(defn- completed-offsets
  "Return a set of chunk_offset ints already written for this blob."
  [conn blob-id]
  (->> (kg/query conn
                 "SELECT chunk_offset FROM chunks WHERE blob_id = ?"
                 blob-id)
       (map :chunk_offset)
       set))

(defn ingest!
  "Store raw payload (String or bytes) to disk, index metadata in the
   file-backed DB. Returns envelope with blob-id."
  [ctx payload {:keys [source]}]
  (with-provenance "loom.blob/ingest!" 1
    (let [data (if (string? payload) (.getBytes payload "UTF-8") payload)
          id   (sha256 data)
          ldir (get-in ctx [:config :loom-dir] ".loom")
          path (str ldir "/blobs/" (date-path) "/" id ".gz")
          src  (or source "unknown")
          size (count data)]
      (write-gzip! path data)
      (kg/write!
        (fn []
          (let [conn (:db-conn ctx)]
            (kg/exec! conn "DELETE FROM blobs WHERE id = ?" id)
            (kg/exec! conn
                      "INSERT INTO blobs (id, path, source, size_bytes)
                       VALUES (?, ?, ?, ?)"
                      id path src size))))
      id)))

(defn ^{:deprecated "v2 — superseded by loom.ingest/ingest-document!. Will be removed when ingest-codebase! lands."}
  chunk!
  "DEPRECATED.  Semantically chunk a blob, summarise each chunk with LLM,
   embed, and store chunk rows.  Resumes from last completed offset.

   Use `loom.ingest/ingest-document!` for markdown / plain-text instead —
   it does heading-aware structural chunking, creates KG `concept` entities
   linked to a `file` entity, and is the v2 canonical authored-content path.
   This fn remains only for `loom.blob/index!`; both will be removed when
   `loom.ingest/ingest-codebase!` lands and absorbs source-file ingestion."
  [ctx blob-id]
  (with-provenance "loom.blob/chunk!" 1
    (let [conn      (:db-conn ctx)
          blob      (or (get-blob-row conn blob-id)
                        (throw (ex-info "blob not found" {:blob-id blob-id})))
          text      (read-raw ctx blob)
          chunks    (semantic-split ctx text (:source blob))
          batches   (partition-all 20 (map-indexed vector chunks))
          done-set  (completed-offsets conn blob-id)]
      (doseq [batch batches]
        (let [new-batch (remove #(done-set (first %)) batch)]
          (when (seq new-batch)
            (let [raw-chunks (mapv second new-batch)
                  summaries  (try
                               (summarize-batch ctx raw-chunks)
                               (catch Exception _
                                 ;; fallback: use raw text as its own summary.
                                 (try
                                   (audit/log! ctx
                                     {:type "system.warning"
                                      :content (str "batch summarization failed for blob "
                                                    blob-id " — using raw text as summary")})
                                   (catch Exception _ nil))
                                 raw-chunks))
                  ;; Embed BEFORE the write thunk so HTTP latency does not
                  ;; serialize the writer queue.
                  rows (mapv (fn [[i chunk] summary]
                               {:id      (str "chunk/" blob-id "/" i)
                                :offset  i
                                :vector  (unwrap! (embedder/embed ctx summary))
                                :summary summary
                                :content chunk})
                             new-batch summaries)]
              (kg/write!
                (fn []
                  (let [conn (:db-conn ctx)]
                    (doseq [{:keys [id offset vector summary content]} rows]
                      (kg/exec! conn "DELETE FROM chunks WHERE id = ?" id)
                      (kg/exec! conn
                                (str "INSERT INTO chunks
                                       (id, blob_id, chunk_offset, vector, summary, content)
                                       VALUES (?, ?, ?, " (vec->sql-literal vector) ", ?, ?)")
                                id blob-id offset summary content)))))))))
      blob-id)))

(defn index!
  "Convenience: ingest + chunk in one call. Returns blob-id.

   v2 note: KG `file`/`chunk` entity creation deferred to future
   `loom.ingest`. Callers that need entities should `kg/upsert-entity!`
   themselves with `:kind \"file\"` and link via DEFINED_IN."
  [ctx payload opts]
  (with-provenance "loom.blob/index!" 1
    (let [blob-id (unwrap! (ingest! ctx payload opts))]
      (chunk! ctx blob-id)
      blob-id)))
