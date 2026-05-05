(ns loom.init
  "Project initialization — walk current directory, ingest key files into Loom.
   No LLM required: files are split by size and embedded directly.
   Run via the /loom-init OpenCode command or call (loom.init/run! ctx) from nREPL.

   v2 (architecture.md): writes go to the file-backed chunks table.
   TODO: this overlaps with the future loom.ingest pipeline; once that
   lands, init should either delegate to ingest or be deleted."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [loom.kg :as kg]
            [loom.embedder :as embedder]
            [loom.envelope :refer [with-provenance unwrap!]]))

;; ---------------------------------------------------------------------------
;; Config
;; ---------------------------------------------------------------------------

(def ^:private ingest-patterns
  "File extensions worth ingesting."
  #{"md" "txt" "clj" "cljs" "cljc" "edn"
    "js" "ts" "tsx" "jsx" "py" "rb" "go"
    "java" "kt" "rs" "cs" "cpp" "c" "h"
    "json" "yaml" "yml" "toml" "xml"
    "sql" "sh" "dockerfile" "tf"})

(def ^:private skip-dirs
  "Directories to skip."
  #{".git" ".loom" "node_modules" "target" "dist" "build"
    ".shadow-cljs" ".cpcache" ".clj-kondo" ".lsp"
    "__pycache__" ".venv" "venv" ".gradle" ".mvn"})

(def ^:private max-file-bytes
  "Skip files larger than this (200 KB)."
  (* 200 1024))

(def ^:private chunk-lines
  "Split large files into chunks of this many lines."
  150)

;; ---------------------------------------------------------------------------
;; File walking
;; ---------------------------------------------------------------------------

(defn- extension [^java.io.File f]
  (let [n (.getName f)
        i (.lastIndexOf n ".")]
    (when (pos? i) (str/lower-case (subs n (inc i))))))

(defn- ingestible? [^java.io.File f]
  (and (.isFile f)
       (< (.length f) max-file-bytes)
       (contains? ingest-patterns (extension f))))

(defn- walk-dir
  "Return all ingestible files under root, skipping skip-dirs."
  [^java.io.File root]
  (let [children (.listFiles root)]
    (when children
      (mapcat (fn [^java.io.File f]
                (cond
                  (and (.isDirectory f) (skip-dirs (.getName f))) []
                  (.isDirectory f) (walk-dir f)
                  (ingestible? f) [f]
                  :else []))
              children))))

;; ---------------------------------------------------------------------------
;; Simple line-based chunking (no LLM needed)
;; ---------------------------------------------------------------------------

(defn- line-chunks [text]
  (->> (str/split-lines text)
       (partition-all chunk-lines)
       (map #(str/join "\n" %))))

;; ---------------------------------------------------------------------------
;; Ingest one file
;; ---------------------------------------------------------------------------

(defn- vec->sql-literal [v]
  (when (and v (seq v))
    (str "[" (str/join ", " (map float v)) "]::FLOAT[768]")))

(defn- ingest-file! [ctx ^java.io.File f root-path]
  (let [rel-path (str/replace (.getAbsolutePath f)
                               (str root-path "/") "")
        text     (slurp f)
        chunks   (line-chunks text)
        ;; embed each chunk before entering the write thunk so HTTP latency
        ;; doesn't serialize the writer queue.
        rows     (mapv (fn [[i chunk]]
                         (let [label (if (> (count chunks) 1)
                                       (str rel-path " [" (inc i) "/" (count chunks) "]")
                                       rel-path)]
                           {:id      (str "chunk/" rel-path "/" i)
                            :blob_id rel-path
                            :offset  i
                            :vector  (unwrap! (embedder/embed ctx label))
                            :summary label
                            :content chunk}))
                       (map-indexed vector chunks))]
    (kg/write!
      (fn []
        (let [conn (:db-conn ctx)]
          (doseq [{:keys [id blob_id offset vector summary content]} rows]
            (kg/exec! conn "DELETE FROM chunks WHERE id = ?" id)
            (kg/exec! conn
                      (str "INSERT INTO chunks
                              (id, blob_id, chunk_offset, vector, summary, content)
                              VALUES (?, ?, ?, " (vec->sql-literal vector) ", ?, ?)")
                      id blob_id offset summary content)))))
    (count chunks)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn run!
  "Walk the current directory (or dir) and ingest all project files into Loom.
   Skips files already indexed (same blob-id = rel-path already in chunks table).
   Returns a summary map."
  ([] (run! nil))
  ([ctx]
   (with-provenance "loom.init/run!" 1
     (let [root     (io/file (System/getProperty "user.dir"))
           files    (walk-dir root)
           total    (count files)
           indexed  (atom 0)
           skipped  (atom 0)
           errors   (atom [])]
       (println (str "[loom/init] Found " total " files in " (.getPath root)))
       (doseq [^java.io.File f files]
         (let [rel-path (str/replace (.getAbsolutePath f)
                                      (str (.getAbsolutePath root) "/") "")]
           (try
             (let [n (ingest-file! ctx f (.getAbsolutePath root))]
               (swap! indexed inc)
               (println (str "  [ok] " rel-path " (" n " chunk" (when (> n 1) "s") ")")))
             (catch Exception e
               (swap! errors conj {:file rel-path :error (.getMessage e)})
               (println (str "  [!]  " rel-path " — " (.getMessage e)))))))
       (let [summary {:total   total
                      :indexed @indexed
                      :skipped @skipped
                      :errors  @errors}]
         (println (str "[loom/init] Done — "
                       @indexed "/" total " files indexed, "
                       (count @errors) " errors."))
         summary)))))
