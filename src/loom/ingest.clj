(ns loom.ingest
  "Authored KG ingestion — populates the project knowledge graph from
   source files. See plans/ingest.md.

   v1 surface (this milestone):
     - `ingest-document!` for markdown / plain-text files

   Reserved (throws on use; v2):
     - `:parallel?`, `:batch-size`, `:llm-extract?` kwargs

   Future namespaces:
     - `ingest-codebase!` — walk a source root, emit module/function entities
     - `resync!` — diff filesystem against KG, retire orphaned entities"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [loom.kg :as kg]
            [loom.blob :as blob]
            [loom.embedder :as embedder]
            [loom.envelope :refer [with-provenance unwrap!]])
  (:import [java.security MessageDigest]))

;; ---------------------------------------------------------------------------
;; Hashing helpers
;; ---------------------------------------------------------------------------

(defn- sha256 ^String [^String s]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md (.getBytes s "UTF-8"))
    (apply str (map #(format "%02x" %) (.digest md)))))

;; ---------------------------------------------------------------------------
;; Markdown chunker — pure
;; ---------------------------------------------------------------------------

(defn- heading-depth
  "If `line` is a markdown heading at a depth ≤ `max-depth`, return the
   depth (1..max-depth). Otherwise nil."
  [max-depth line]
  (when-let [hashes (second (re-matches #"^(#{1,6})\s+.+" (or line "")))]
    (let [d (count hashes)]
      (when (<= d max-depth) d))))

(defn- split-into-sections
  "Cut text at heading lines (whose depth is ≤ `max-depth`).
   Each returned string starts with its own heading line (or, for the very
   first section, with whatever lead-in text appeared before any heading)."
  [text max-depth]
  (let [lines    (str/split-lines (or text ""))
        sections (atom [])
        current  (atom [])]
    (doseq [line lines]
      (if (heading-depth max-depth line)
        (do
          (when (seq @current)
            (swap! sections conj (str/join "\n" @current)))
          (reset! current [line]))
        (swap! current conj line)))
    (when (seq @current)
      (swap! sections conj (str/join "\n" @current)))
    (vec @sections)))

(defn- extract-heading
  "Return `[heading-text body]`. heading-text is nil when section has none."
  [section max-depth]
  (let [lines (str/split-lines section)
        first-line (first lines)]
    (if (and first-line (heading-depth max-depth first-line))
      [first-line (str/join "\n" (rest lines))]
      [nil section])))

(defn- split-paragraphs
  "Split a body into paragraph blocks on blank-line boundaries."
  [text]
  (->> (str/split (or text "") #"\n\s*\n+")
       (mapv str/trim)
       (filterv (complement str/blank?))))

(defn- hard-cut
  "Force-split `s` into substrings of at most `max-chars`. UTF-16 code
   units, not graphemes — emoji/surrogate pairs near a cut point may be
   damaged. Acceptable in v1; rare in practice."
  [s max-chars]
  (loop [s s, acc []]
    (if (<= (count s) max-chars)
      (conj acc s)
      (recur (subs s max-chars) (conj acc (subs s 0 max-chars))))))

(defn- pack-paragraphs
  "Greedily pack paragraphs into chunks, each ≤ max-chars.
   A single paragraph longer than max-chars gets hard-cut first."
  [paragraphs max-chars]
  (let [normalised (mapcat #(hard-cut % max-chars) paragraphs)
        packed     (atom [])
        current    (atom nil)]
    (doseq [p normalised]
      (let [combined (if @current (str @current "\n\n" p) p)]
        (if (and @current (> (count combined) max-chars))
          (do (swap! packed conj @current)
              (reset! current p))
          (reset! current combined))))
    (when @current (swap! packed conj @current))
    (vec @packed)))

(defn- chunk-section
  "Produce 1+ chunk maps for a single section.
     {:heading nil-or-string :body string}
   The heading line, when present, is also included verbatim at the start
   of the first chunk's body — so the chunk text the embedder sees has its
   heading context."
  [section heading max-chars]
  (if (<= (count section) max-chars)
    [{:heading heading :body section}]
    (let [paras  (split-paragraphs section)
          bodies (pack-paragraphs paras max-chars)]
      (mapv (fn [body] {:heading heading :body body}) bodies))))

(defn chunk-markdown
  "Pure: chunk text into a vec of `{:heading :body :offset}`.

   opts:
     :max-chunk-chars  default 1000 — soft cap; long paragraphs are hard-cut
     :min-chunk-chars  default 50   — chunks shorter than this are dropped
     :heading-depth    default 4    — H1..H{this} are chunk boundaries"
  ([text] (chunk-markdown text {}))
  ([text {:keys [max-chunk-chars min-chunk-chars heading-depth]
          :or   {max-chunk-chars 1000
                 min-chunk-chars 50
                 heading-depth   4}}]
   (let [text (or text "")]
     (if (str/blank? text)
       []
       (let [sections (split-into-sections text heading-depth)
             chunks   (mapcat
                        (fn [section]
                          (let [[hd _body] (extract-heading section heading-depth)]
                            (chunk-section section hd max-chunk-chars)))
                        sections)
             kept     (filterv #(>= (count (str/trim (:body %))) min-chunk-chars)
                               chunks)]
         (vec (map-indexed
                (fn [i c] (assoc c :offset i))
                kept)))))))

;; ---------------------------------------------------------------------------
;; Reserved-kwarg validation
;; ---------------------------------------------------------------------------

(defn- validate-reserved! [opts]
  (when (true? (:parallel? opts))
    (throw (ex-info "ingest-document!: :parallel? not implemented in v1"
                    {:reason :parallel-pending})))
  (when (some? (:batch-size opts))
    (throw (ex-info "ingest-document!: :batch-size not implemented in v1"
                    {:reason :batch-pending :batch-size (:batch-size opts)})))
  (when (true? (:llm-extract? opts))
    (throw (ex-info "ingest-document!: :llm-extract? not implemented in v1"
                    {:reason :llm-extract-pending}))))

;; ---------------------------------------------------------------------------
;; Doc identity
;; ---------------------------------------------------------------------------

(defn- doc-entity-id [abs-path]
  (str "file:" (sha256 abs-path)))

(defn- concept-id [chunk-text]
  (str "concept/" (sha256 chunk-text)))

(defn- guess-title [text basename]
  (or (when text
        (some->> (str/split-lines text)
                 (some (fn [line]
                         (when-let [[_ _ rest] (re-matches #"^(#{1,6})\s+(.+)$" line)]
                           (str/trim rest))))))
      basename))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn ingest-document!
  "Ingest a markdown / plain-text file into the project KG.

   Pipeline (single `kg/write!` thunk):
     1. Read content + sha256.
     2. Chunk via `chunk-markdown`.
     3. Embed each chunk and the title (pre-thunk; HTTP latency does not
        serialise the writer queue).
     4. blob row + N chunks rows + 1 doc entity (kind=\"file\") + N concept
        entities + N PART_OF relations, all in one thunk.

   Idempotent on unchanged content: deterministic ids derived from sha256.

   opts:
     :title?            override the title (default: first heading or basename)
     :project?          string tag, recorded in attrs
     :as?               :doc | :business-model — recorded in attrs.role
                        (does NOT change the doc entity's kind)
     :tags?             extra tags carried in attrs
     :max-chunk-chars?  default 1000
     :min-chunk-chars?  default 50
     :heading-depth?    default 4
     :parallel?         RESERVED — throws if true
     :batch-size?       RESERVED — throws if non-nil
     :llm-extract?      RESERVED — throws if true

   Returns {:doc-id … :chunks N :entities (+ N 1) :relations N}."
  ([ctx path] (ingest-document! ctx path {}))
  ([ctx path opts]
   (with-provenance "loom.ingest/ingest-document!" 1
     (validate-reserved! opts)
     (let [file (io/file path)
           _    (when-not (.exists file)
                  (throw (ex-info "ingest-document!: file not found"
                                  {:path path})))
           abs-path (.getAbsolutePath file)
           text     (slurp file)
           basename (.getName file)
           title    (or (:title opts) (guess-title text basename))
           role     (some-> (:as opts) name)
           project  (:project opts)
           tags     (vec (concat ["doc"] (or (:tags opts) [])))
           chunks   (chunk-markdown text
                                    (select-keys opts
                                                 [:max-chunk-chars
                                                  :min-chunk-chars
                                                  :heading-depth]))
           ;; Embed everything BEFORE entering the write thunk.
           title-vec (unwrap! (embedder/embed ctx title))
           chunk-rows
           (mapv (fn [{:keys [body offset heading]}]
                   {:body    body
                    :offset  offset
                    :heading heading
                    :concept-id (concept-id body)
                    :vector  (unwrap! (embedder/embed ctx body))})
                 chunks)
           ;; Persist the gzipped blob + blobs row (its own write thunk).
           blob-id  (unwrap! (blob/ingest! ctx text {:source abs-path}))
           doc-id   (doc-entity-id abs-path)
           doc-attrs (cond-> {:title title :path abs-path :tags tags}
                       project (assoc :project project)
                       role    (assoc :role role)
                       blob-id (assoc :blob_id blob-id))]
       ;; Single compound write: doc entity → concepts → PART_OF → chunks.
       (kg/write!
         (fn []
           (let [conn (:db-conn ctx)]
             ;; doc entity (always kind="file"; :as is metadata only)
             (kg/upsert-entity* ctx
               {:id doc-id
                :kind "file"
                :canonical_name title
                :aliases [title basename abs-path]
                :attrs doc-attrs
                :vector title-vec})
             ;; concept entities
             (doseq [{:keys [concept-id body offset heading vector]} chunk-rows]
               (kg/upsert-entity* ctx
                 {:id concept-id
                  :kind "concept"
                  :canonical_name body
                  :aliases (if heading [heading] [])
                  :attrs (cond-> {:chunk_offset offset :doc_id doc-id}
                           heading (assoc :heading heading)
                           project (assoc :project project))
                  :vector vector}))
             ;; PART_OF relations
             (doseq [{:keys [concept-id offset]} chunk-rows]
               (kg/upsert-relation* ctx
                 {:id (str "rel/" concept-id "/PART_OF/" doc-id)
                  :subject_id concept-id
                  :predicate "PART_OF"
                  :object_id doc-id
                  :attrs {:chunk_offset offset
                          :source_table "ingest"}}))
             ;; chunks-table rows (RAG retrieval surface)
             (doseq [{:keys [body offset vector heading]} chunk-rows]
               (let [vec-frag (str "[" (str/join ", " (map float vector)) "]::FLOAT[768]")
                     row-id   (str "chunk/" doc-id "/" offset)]
                 (kg/exec! conn "DELETE FROM chunks WHERE id = ?" row-id)
                 (kg/exec! conn
                           (str "INSERT INTO chunks
                                  (id, blob_id, chunk_offset, vector, summary, content)
                                  VALUES (?, ?, ?, " vec-frag ", ?, ?)")
                           row-id blob-id offset (or heading "") body))))))
       {:doc-id doc-id
        :chunks (count chunk-rows)
        :entities (+ (count chunk-rows) 1)
        :relations (count chunk-rows)}))))
