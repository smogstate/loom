(ns loom.ingest-test
  "Coverage for loom.ingest/ingest-document! and chunk-markdown.

   Pure chunker tests run without a DB; integration tests use a fixture
   that stubs embedder/embed (no Ollama required)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [loom.kg :as kg]
            [loom.ingest :as ingest]
            [loom.embedder :as embedder]
            [loom.envelope :refer [unwrap!]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Pure chunker tests
;; ---------------------------------------------------------------------------

(deftest chunk-markdown-empty-input
  (is (= [] (ingest/chunk-markdown nil)))
  (is (= [] (ingest/chunk-markdown "")))
  (is (= [] (ingest/chunk-markdown "   \n\n  "))))

(deftest chunk-markdown-headings-split
  (let [text "# Title

intro paragraph that is reasonably long enough to keep.

## Section A

Section A body content goes here. It has enough characters.

## Section B

Section B body content goes here. Also enough characters."
        chunks (ingest/chunk-markdown text)]
    (is (= 3 (count chunks)))
    (is (= ["# Title" "## Section A" "## Section B"]
           (mapv :heading chunks)))
    (is (= [0 1 2] (mapv :offset chunks)))))

(deftest chunk-markdown-no-headings-paragraph-fallback
  (let [text "First paragraph that is long enough to satisfy the minimum chunk size threshold.

Second paragraph that also is long enough to satisfy the minimum size threshold so it gets kept.

Third paragraph also long enough to satisfy the minimum chunk size threshold here as well."
        chunks (ingest/chunk-markdown text {:max-chunk-chars 200})]
    (is (>= (count chunks) 1))
    (is (every? #(nil? (:heading %)) chunks))))

(deftest chunk-markdown-respects-min-chunk-chars
  (let [text "# X\n\nshort\n\n## Y\n\nstill short"
        chunks (ingest/chunk-markdown text {:min-chunk-chars 100})]
    (is (= [] chunks)
        "All chunks below min-chunk-chars are dropped")))

(deftest chunk-markdown-deeply-nested-headings-respect-depth
  (let [text "# H1\n\nbody for H1 that is long enough to keep around.\n\n###### H6\n\nbody for H6 that is long enough to keep too."
        chunks-default (ingest/chunk-markdown text)
        chunks-deep    (ingest/chunk-markdown text {:heading-depth 6})]
    (testing "default heading-depth=4 ignores H6"
      (is (= 1 (count chunks-default))))
    (testing "heading-depth=6 splits at H6"
      (is (= 2 (count chunks-deep))))))

(deftest chunk-markdown-hard-cuts-overlong-paragraph
  (let [huge (apply str (repeat 3000 "x"))
        chunks (ingest/chunk-markdown huge {:max-chunk-chars 1000
                                            :min-chunk-chars 1})]
    (is (= 3 (count chunks)))
    (is (every? #(<= (count (:body %)) 1000) chunks))))

(deftest chunk-markdown-unicode-safe
  (let [text (str "# Greetings 🎉\n\n"
                  (apply str (repeat 60 "résumé café ")))
        chunks (ingest/chunk-markdown text)]
    (is (pos? (count chunks)))
    (is (str/includes? (-> chunks first :body) "résumé"))))

;; ---------------------------------------------------------------------------
;; Integration fixture
;; ---------------------------------------------------------------------------

(def ^:dynamic *ctx* nil)

(defn- temp-dir []
  (str (Files/createTempDirectory "loom-ingest-test-" (into-array FileAttribute []))))

;; Deterministic faux embedder: maps each text to a vector with a single
;; "spike" derived from a hash of the text, so semantic search returns the
;; same chunk text as the top match.
(defn- text->vec [^String s]
  (let [h (Math/abs (long (hash s)))
        idx (mod h 768)
        v (vec (repeat 768 0.0))]
    (assoc v idx 1.0)))

(defn ingest-fixture [f]
  (let [ldir    (temp-dir)
        db-conn (kg/connect-file! ldir)]
    (kg/init-schema! db-conn)
    (kg/start-writer!)
    (binding [*ctx* {:db-conn db-conn :session-id "s1" :config {:loom-dir ldir}}]
      (with-redefs [embedder/embed
                    (fn [_ctx text]
                      {:ok? true :result (text->vec text) :provenance {} :error nil})]
        (try (f)
             (finally (.close db-conn)))))))

(use-fixtures :each ingest-fixture)

(defn- write-tmp! [name content]
  (let [path (str (get-in *ctx* [:config :loom-dir]) "/" name)]
    (spit path content)
    path))

;; ---------------------------------------------------------------------------
;; Reserved-kwarg rejection
;; ---------------------------------------------------------------------------

(deftest rejects-llm-extract
  (let [path (write-tmp! "x.md" "# X\n\nbody long enough to keep around.")]
    (is (thrown-with-msg? Exception #":llm-extract"
          (unwrap! (ingest/ingest-document! *ctx* path {:llm-extract? true}))))))

(deftest rejects-parallel
  (let [path (write-tmp! "x.md" "# X\n\nbody long enough to keep around.")]
    (is (thrown-with-msg? Exception #":parallel"
          (unwrap! (ingest/ingest-document! *ctx* path {:parallel? true}))))))

(deftest rejects-batch-size
  (let [path (write-tmp! "x.md" "# X\n\nbody long enough to keep around.")]
    (is (thrown-with-msg? Exception #":batch-size"
          (unwrap! (ingest/ingest-document! *ctx* path {:batch-size 10}))))))

;; ---------------------------------------------------------------------------
;; End-to-end ingestion
;; ---------------------------------------------------------------------------

(def ^:private sample
  "# Payments

Payments are processed nightly using a batch worker. The worker reads from
the inbox queue and writes to the ledger table.

## Refunds

Refunds reverse a charge by creating a negative ledger entry. They are
audited for fraud signals before posting.")

(deftest ingest-returns-shape
  (let [path (write-tmp! "doc.md" sample)
        r    (unwrap! (ingest/ingest-document! *ctx* path {}))]
    (is (string? (:doc-id r)))
    (is (= 2 (:chunks r)))
    (is (= 3 (:entities r)) "1 doc + 2 concept entities")
    (is (= 2 (:relations r)) "2 PART_OF edges")))

(deftest ingest-creates-rows
  (let [path (write-tmp! "doc.md" sample)
        {:keys [doc-id]} (unwrap! (ingest/ingest-document! *ctx* path {}))
        conn (:db-conn *ctx*)
        n-chunks   (-> (kg/query conn "SELECT count(*) AS c FROM chunks") first :c int)
        n-blobs    (-> (kg/query conn "SELECT count(*) AS c FROM blobs") first :c int)
        n-entities (count (unwrap! (kg/query-entities *ctx* {})))
        n-rel      (count (unwrap! (kg/query-relations *ctx* {})))
        doc        (first (unwrap! (kg/query-entities *ctx* {:ids [doc-id]})))]
    (is (= 1 n-blobs))
    (is (= 2 n-chunks))
    (is (= 3 n-entities))
    (is (= 2 n-rel))
    (is (= "file" (:kind doc)))
    (is (= "Payments" (:canonical_name doc)))))

(deftest ingest-is-idempotent
  (let [path (write-tmp! "doc.md" sample)]
    (unwrap! (ingest/ingest-document! *ctx* path {}))
    (unwrap! (ingest/ingest-document! *ctx* path {}))
    (let [conn (:db-conn *ctx*)
          n-chunks   (-> (kg/query conn "SELECT count(*) AS c FROM chunks") first :c int)
          n-blobs    (-> (kg/query conn "SELECT count(*) AS c FROM blobs") first :c int)
          n-entities (count (unwrap! (kg/query-entities *ctx* {})))
          n-rel      (count (unwrap! (kg/query-relations *ctx* {})))]
      (is (= 1 n-blobs))
      (is (= 2 n-chunks))
      (is (= 3 n-entities))
      (is (= 2 n-rel)))))

(deftest ingest-empty-file
  (let [path (write-tmp! "empty.md" "")
        r    (unwrap! (ingest/ingest-document! *ctx* path {}))]
    (is (= 0 (:chunks r)))
    (is (= 1 (:entities r)) "doc entity is created even with no chunks")
    (is (= 0 (:relations r)))))

(deftest ingest-no-headings-uses-paragraph-fallback
  (let [text "First paragraph long enough to count as a real chunk for ingestion purposes.\n\nSecond paragraph long enough to count as a real chunk for ingestion purposes."
        path (write-tmp! "plain.md" text)
        r    (unwrap! (ingest/ingest-document! *ctx* path {:max-chunk-chars 200}))]
    (is (>= (:chunks r) 1))))

(deftest ingest-part-of-edges-point-at-doc
  (let [path (write-tmp! "doc.md" sample)
        {:keys [doc-id]} (unwrap! (ingest/ingest-document! *ctx* path {}))
        ns (unwrap! (kg/neighbors *ctx* doc-id {:direction :in
                                                :predicates ["PART_OF"]}))]
    (is (= 2 (count ns)))
    (is (every? #(= "PART_OF" (:predicate %)) ns))))

(deftest ingest-semantic-relevance
  (testing "querying with the literal text of a chunk returns that chunk first"
    (let [path (write-tmp! "doc.md" sample)
          _    (unwrap! (ingest/ingest-document! *ctx* path {}))
          ;; sample's first chunk's body literally contains the heading "# Payments"
          ;; followed by the payments paragraph; second chunk is "## Refunds" + body.
          chunks (unwrap! (kg/query-entities *ctx* {:kind "concept"}))
          payments-chunk (some #(when (str/includes? (:canonical_name %) "Payments") %) chunks)
          q (text->vec (:canonical_name payments-chunk))
          ranked (unwrap! (kg/query-entities *ctx* {:kind "concept" :vector q :limit 2}))]
      (is (= (:id payments-chunk) (-> ranked first :id))
          "ranked top should be the very chunk we used as query text"))))

(deftest ingest-uses-single-write-thunk
  (let [path (write-tmp! "doc.md" sample)
        calls (atom 0)
        ;; loom.blob/ingest! also enters one write thunk; count BOTH and assert 2.
        orig  kg/write!]
    (with-redefs [kg/write! (fn [f] (swap! calls inc) (orig f))]
      (unwrap! (ingest/ingest-document! *ctx* path {})))
    (is (= 2 @calls)
        "exactly 2 thunks: blob/ingest! + ingest-document!'s compound write")))

(deftest ingest-rejects-missing-file
  (is (thrown-with-msg? Exception #"file not found"
        (unwrap! (ingest/ingest-document! *ctx* "/no/such/path.md" {})))))
