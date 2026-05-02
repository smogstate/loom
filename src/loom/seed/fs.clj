(ns loom.seed.fs
  "Filesystem tools: read, write, list, search."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
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

(defn search-source
  "Search source files for a pattern using ripgrep.
   Returns up to 50 matches as {:file :line :text} maps.
   Use this to verify exact line numbers and signatures before citing code."
  {:doc "Grep source files by regex using rg. Returns file path, line number, matched text. Use before citing code in conclusions."
   :tags ["fs" "search" "grep" "source" "verify" "code" "rg"]}
  ([ctx pattern] (search-source ctx pattern "."))
  ([ctx pattern dir]
   (with-provenance "loom.seed.fs/search-source" 1
      (let [proc (-> (ProcessBuilder. ["/usr/bin/rg" "--line-number" "--no-heading"
                                       "--max-count" "50"
                                       "--glob" "*.clj"
                                       pattern dir])
                    (.redirectErrorStream true)
                    .start)
           out  (slurp (.getInputStream proc))
           _    (.waitFor proc)]
        (->> (str/split-lines out)
             (filter seq)
             (mapv (fn [line]
                     (let [[file ln text] (str/split line #":" 3)]
                       (if (and file ln text)
                         {:file file :line (parse-long (str/trim ln)) :text (str/trim text)}
                         {:file line :line nil :text line})))))))))
