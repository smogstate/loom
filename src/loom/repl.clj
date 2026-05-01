(ns loom.repl
  "nREPL server management — start/stop the nREPL on port 7888."
  (:require [nrepl.server :as nrepl]
            [cider.nrepl :refer [cider-middleware]]))

(defonce ^:private server (atom nil))

(defn start!
  "Start the nREPL server on port 7888. Idempotent."
  ([] (start! 7888))
  ([port]
   (when-not @server
     (let [s (nrepl/start-server
               :port port
               :handler (apply nrepl/default-handler cider-middleware))]
       (reset! server s)
       (println (str "[loom] nREPL started on port " port))))
   @server))

(defn stop!
  "Stop the nREPL server."
  []
  (when-let [s @server]
    (nrepl/stop-server s)
    (reset! server nil)
    (println "[loom] nREPL stopped.")))

(defn running? [] (some? @server))
