(ns loom.core
  "Entry point. Builds ctx, bootstraps DB, starts nREPL for external agents."
  (:require [loom.db :as db]
            [loom.embedder :as embedder]
            [loom.state :as state]
            [loom.tools :as tools]
            [loom.guard :as guard]
            [loom.scratch :as scratch]
            [loom.repl :as repl]
            [clojure.java.io :as io]
            [loom.envelope :refer [unwrap!]]))

;; ---------------------------------------------------------------------------
;; ctx factory
;; ---------------------------------------------------------------------------

(defn make-ctx
  "Build the ctx map. loom-dir defaults to .loom."
  ([] (make-ctx {}))
  ([{:keys [loom-dir ollama-url models session-id]
     :or   {loom-dir   ".loom"
            ollama-url "http://localhost:11434"
            models     {:cheap  "claude-haiku-4-5"
                        :strong "claude-sonnet-4-5"}}}]
   (let [sid  (or session-id (str (java.util.UUID/randomUUID)))
         conn (db/connect!)]
     ;; ensure dirs
     (.mkdirs (io/file loom-dir "sessions" sid))
     (db/start-writer!)
     {:conn       conn
      :embedder   (fn [text] (embedder/embed {:config {:ollama-url ollama-url}} text))
      :state      state/session-state
      :session-id sid
      :config     {:models     models
                   :ollama-url ollama-url
                   :loom-dir   loom-dir
                   :max-turns  20}})))

;; ---------------------------------------------------------------------------
;; Bootstrap — seed tool library on first boot
;; ---------------------------------------------------------------------------

(def ^:private seed-namespaces
  '[loom.seed.http loom.seed.fs loom.seed.text
    loom.seed.data loom.seed.math loom.seed.db
    loom.seed.project loom.seed.eval
    loom.goals])

(defn bootstrap!
  "Seed the tool library. Registers any seed tool not yet in the DB."
  [ctx]
  (println "[loom] Bootstrapping tool library...")
  (doseq [ns-sym seed-namespaces]
    (tools/scan-ns! ctx ns-sym))
  (println (str "[loom] Tools ready: " (unwrap! (db/table-count ctx :tools)) " total.")))

;; ---------------------------------------------------------------------------
;; Session start — ingest LOOM.md if present
;; ---------------------------------------------------------------------------

(defn maybe-ingest-loom-md!
  "If LOOM.md exists in the current directory, ingest it."
  [ctx]
  (let [f (java.io.File. "LOOM.md")]
    (when (.exists f)
      (println "[loom] LOOM.md found — ingesting project facts...")
      (require 'loom.seed.project)
      ((resolve 'loom.seed.project/ingest-project-md!)
       ctx "LOOM.md" {:project (.getName (java.io.File. "."))}))))

;; ---------------------------------------------------------------------------
;; Public start fn
;; ---------------------------------------------------------------------------

(defn start!
  "Start loom: connect DB, bootstrap, start nREPL. Returns ctx."
  ([] (start! {}))
  ([opts]
   (let [ctx (make-ctx opts)]
     (guard/init! ctx)
     (bootstrap! ctx)
     (require 'loom.budget)
     ((resolve 'loom.budget/init!) ctx)
     (scratch/load-all! ctx)
     (maybe-ingest-loom-md! ctx)
     (tools/start-watcher! ctx)
     (repl/start!)
     (println "[loom] Ready.")
     ctx)))
