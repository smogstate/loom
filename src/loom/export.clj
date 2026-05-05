(ns loom.export
  "Export the Loom DB to Parquet on demand.
   Loom v2 stores everything in a single DuckDB file (.loom/loom.db plus
   attached .loom/usage.db). For sharing, backup, or external analytics
   pipelines you can dump the contents to columnar Parquet without paying
   the Parquet round-trip cost on every write — that was the v1 default;
   v2 makes it explicit and on-demand.

   See plans/architecture.md §8 'Out of Scope (Follow-ups)'."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [loom.kg :as kg]
            [loom.envelope :refer [with-provenance]]))

(defn export!
  "Dump the entire main DB to Parquet via DuckDB's EXPORT DATABASE.
   Writes one file per table under `out-dir`. Does NOT include the
   attached `loom_usage` schema — call `export-usage!` for that.
   Returns the absolute out-dir."
  [ctx out-dir]
  (with-provenance "loom.export/export!" 1
    (let [out (-> (io/file out-dir) .getAbsolutePath)]
      (.mkdirs (io/file out))
      (kg/exec! (:db-conn ctx)
                (str "EXPORT DATABASE '" out "' (FORMAT PARQUET)"))
      out)))

(defn export-usage!
  "Dump only the attached usage telemetry schema to a parquet file."
  [ctx out-dir]
  (with-provenance "loom.export/export-usage!" 1
    (let [out  (-> (io/file out-dir) .getAbsolutePath)
          path (str out "/usage.parquet")]
      (.mkdirs (io/file out))
      (kg/exec! (:db-conn ctx)
                (str "COPY (SELECT * FROM loom_usage.usage) TO '" path
                     "' (FORMAT PARQUET)"))
      path)))

(defn- export-table!
  "Dump a single table to Parquet."
  [ctx table out-dir]
  (let [path (str out-dir "/" (name table) ".parquet")]
    (kg/exec! (:db-conn ctx)
              (str "COPY (SELECT * FROM " (name table) ") TO '" path
                   "' (FORMAT PARQUET)"))
    path))

(defn export-kg!
  "Dump just the KG tables (entities + relations) — the most useful
   subset to share between Loom instances or feed into external graph
   tools. Returns a vec of written file paths."
  [ctx out-dir]
  (with-provenance "loom.export/export-kg!" 1
    (let [out (-> (io/file out-dir) .getAbsolutePath)]
      (.mkdirs (io/file out))
      (mapv #(export-table! ctx % out) [:entities :relations]))))
