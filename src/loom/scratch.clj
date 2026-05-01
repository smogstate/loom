(ns loom.scratch
  "Session-scoped scratch tool lifecycle.
   Scratch tools live in ./scratch/<name>.clj, are loaded at session start,
   tracked by hit count in the session parquet, and can be promoted to a
   permanent loom.seed.<domain> namespace.
   Nothing is deleted — forgotten session parquets persist in .loom/sessions/."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [loom.db :as db]
            [loom.embedder :as embedder]
            [loom.tools :as tools]
            [loom.envelope :refer [with-provenance unwrap!]]))

(def ^:private hit-threshold 5)

;; ---------------------------------------------------------------------------
;; Scratch dir
;; ---------------------------------------------------------------------------

(defn- scratch-dir
  "Absolute path to the scratch directory."
  []
  (-> (io/file "scratch") .getAbsolutePath))

(defn- scratch-file
  "Path to the .clj file for a given tool slug."
  [slug]
  (str (scratch-dir) "/" slug ".clj"))

;; ---------------------------------------------------------------------------
;; Create a scratch tool
;; ---------------------------------------------------------------------------

(defn create!
  "Write a scratch tool .clj file, load it, and register it in the session parquet.
   - slug: short name e.g. \"slugify\"
   - doc: one-line description
   - tags: vector of strings
   - code-body: the function body as a string (args + body, no defn wrapper)
   
   Example:
     (create! ctx \"slugify\" \"Convert a string to a URL slug.\" [\"text\" \"string\"]
              \"[s] (-> s str/lower-case (str/replace #\\\"[^a-z0-9]+\\\" \\\"-\\\"))\")"
  [ctx slug doc tags code-body]
  (with-provenance "loom.scratch/create!" 1
    (let [ns-name  (str "loom.scratch." slug)
          fn-name  slug
          file     (scratch-file slug)
          full-code (str "(ns " ns-name "\n"
                         "  (:require [clojure.string :as str]))\n\n"
                         "(defn ^{:doc " (pr-str doc) "\n"
                         "        :tags " (pr-str tags) "}\n"
                         "  " fn-name "\n"
                         "  " code-body ")")]
      ;; write file
      (spit file full-code)
      ;; load into nREPL
      (load-file file)
      ;; register in session parquet
      (let [var-sym  (symbol ns-name fn-name)
            v        (resolve var-sym)
            _        (when-not v (throw (ex-info "Could not resolve scratch var after load-file"
                                                 {:sym var-sym})))
            text     (str/join " " [ns-name fn-name doc (str/join " " tags)])
            vec      (unwrap! (embedder/embed ctx text))]
        (unwrap! (db/save-scratch-tool! ctx
                   {:name  (str ns-name "/" fn-name)
                    :doc   doc
                    :tags  tags
                    :vector vec
                    :code  full-code
                    :file  file}))
        ;; also put in state mirror for in-process lookup
        (loom.state/add-tool! {:name   (str ns-name "/" fn-name)
                               :doc    doc
                               :tags   tags
                               :vector vec
                               :code   full-code
                               :fn     v})
        (str ns-name "/" fn-name)))))

;; ---------------------------------------------------------------------------
;; Load existing scratch files at session start
;; ---------------------------------------------------------------------------

(defn load-all!
  "load-file every .clj in ./scratch/ and register them in the session parquet.
   Idempotent — safe to call at every session start."
  [ctx]
  (with-provenance "loom.scratch/load-all!" 1
    (let [dir (io/file (scratch-dir))]
      (when (.exists dir)
        (let [files (->> (.listFiles dir)
                         (filter #(str/ends-with? (.getName %) ".clj")))]
          (doseq [f files]
            (let [slug     (str/replace (.getName f) #"\.clj$" "")
                  ns-name  (str "loom.scratch." slug)
                  fn-name  slug]
              (try
                (load-file (.getAbsolutePath f))
                (let [var-sym (symbol ns-name fn-name)
                      v       (resolve var-sym)]
                  (when v
                    (let [m    (meta v)
                          doc  (or (:doc m) "")
                          tags (or (:tags m) [])
                          text (str/join " " [ns-name fn-name doc (str/join " " tags)])
                          vec  (unwrap! (embedder/embed ctx text))]
                      (unwrap! (db/save-scratch-tool! ctx
                                 {:name  (str ns-name "/" fn-name)
                                  :doc   doc
                                  :tags  tags
                                  :vector vec
                                  :code  (slurp f)
                                  :file  (.getAbsolutePath f)}))
                      (loom.state/add-tool! {:name   (str ns-name "/" fn-name)
                                             :doc    doc
                                             :tags   tags
                                             :vector vec
                                             :fn     v}))))
                (catch Exception e
                  (println "[scratch] Failed to load" (.getName f) "-" (.getMessage e))))))
          (println (str "[scratch] Loaded " (count files) " scratch tool(s).")))))))

;; ---------------------------------------------------------------------------
;; Hit tracking
;; ---------------------------------------------------------------------------

(defn track-hit!
  "Increment the session hit count for a scratch tool name.
   Returns {:count n :promote? true} at threshold, {:count n} otherwise."
  [ctx tool-name]
  (with-provenance "loom.scratch/track-hit!" 1
    (let [n (unwrap! (db/inc-hit! ctx tool-name))]
      (if (= n hit-threshold)
        {:count n :promote? true
         :message (str "Scratch tool '" tool-name "' hit " n " uses. "
                       "Promote with: (loom.scratch/promote! ctx \""
                       tool-name "\" \"<domain>\")")}
        {:count n :promote? false}))))

(defn scratch-tool?
  "True if tool-name is a scratch tool (starts with loom.scratch.)."
  [tool-name]
  (str/starts-with? (str tool-name) "loom.scratch."))

;; ---------------------------------------------------------------------------
;; Promotion
;; ---------------------------------------------------------------------------

(defn promote!
  "Promote a scratch tool to a permanent loom.seed.<domain> namespace.
   - tool-name: fully qualified e.g. \"loom.scratch.slugify/slugify\"
   - domain: target seed domain e.g. \"text\", \"fs\", \"math\"
   
   Reads the scratch .clj, rewrites the ns declaration to loom.seed.<domain>,
   appends to (or creates) src/loom/seed/<domain>.clj, then re-registers
   the tool in the global parquet. Session parquet is left untouched."
  [ctx tool-name domain]
  (with-provenance "loom.scratch/promote!" 1
    (let [scratch-rec (unwrap! (db/get-scratch-tool ctx tool-name))
          _           (when-not scratch-rec
                        (throw (ex-info "Scratch tool not found" {:name tool-name})))
          slug        (-> tool-name (str/split #"/") last)
          old-ns      (str "loom.scratch." slug)
          new-ns      (str "loom.seed." domain)
          seed-file   (str "src/loom/seed/" domain ".clj")
          old-code    (:code scratch-rec)
          ;; rewrite ns declaration
          new-code    (str/replace old-code
                                   (re-pattern (str "\\(ns " old-ns))
                                   (str "(ns " new-ns))]
      ;; create seed file if it doesn't exist
      (when-not (.exists (io/file seed-file))
        (spit seed-file (str "(ns " new-ns "\n  \"Promoted tools.\")\n")))
      ;; append the function (strip everything before the first defn)
      (let [fn-only (-> new-code
                        (str/replace #"(?s)^.*?(?=\(defn)" "")
                        str/trim)]
        (spit seed-file (str "\n\n" fn-only) :append true))
      ;; load and register in global parquet
      (load-file seed-file)
      (let [var-sym (symbol new-ns slug)]
        (unwrap! (tools/register! ctx var-sym)))
      (println (str "[scratch] '" tool-name "' promoted to " new-ns "/" slug))
      (str new-ns "/" slug))))
