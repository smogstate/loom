(ns loom.seed.fs
  "Filesystem tools: read, write, list."
  (:require [clojure.java.io :as io]
            [loom.envelope :refer [with-provenance]]))

(defn read-file
  "Read a file and return its contents as a string."
  {:doc "Read file at path, return contents as string."
   :tags ["fs" "read" "file"]}
  [ctx path]
  (with-provenance "loom.seed.fs/read-file" 1
    (slurp path)))

(defn write-file
  "Write content string to path (creates parent dirs)."
  {:doc "Write string content to a file. Creates parent directories if needed."
   :tags ["fs" "write" "file"]}
  [ctx path content]
  (with-provenance "loom.seed.fs/write-file" 1
    (io/make-parents path)
    (spit path content)
    path))

(defn list-dir
  "List entries in a directory. Returns sequence of file paths."
  {:doc "List files and directories under path. Returns vector of path strings."
   :tags ["fs" "list" "directory"]}
  [ctx path]
  (with-provenance "loom.seed.fs/list-dir" 1
    (->> (file-seq (io/file path))
         (mapv str))))
