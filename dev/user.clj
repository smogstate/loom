(ns user
  "REPL utilities. Loom should be started via OpenCode commands (/loom-init, /start-loom)."
  (:require [dev]
            [loom.core :as loom]
            [loom.kg :as kg]))

(defonce ctx nil)

(defn start! []
  (alter-var-root #'ctx (constantly (loom/start!)))
  (println "[user] loom started, ctx bound.")
  ctx)

;; To start manually from REPL: (start!)
