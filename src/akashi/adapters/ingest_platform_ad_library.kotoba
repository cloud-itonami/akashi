(ns akashi.adapters.ingest-platform-ad-library
  "Ingest reviewed local platform ad-library exports. This is an offline file
  boundary: operators provide already-reviewed Meta/Instagram/X-style JSON
  snapshots, and this adapter validates and projects them to EDN."
  (:require [cheshire.core :as json]
            [akashi.adapters.dry-run-fixtures :as dry-run]
            [akashi.adapters.edn-export :as export]
            [akashi.adapters.platform-ad-library-fixture-parser :as parser]))

(def ^:private attesting-did-default "did:web:akashi.etzhayyim.com")
(def ^:private default-source-policy-cid "cid:akashi:source-policy:reviewed-platform-export")
(def ^:private default-method-note-cid "cid:akashi:method-note:platform-export-ingest")

(defn- load-json [path]
  (json/parse-string (slurp path)))

(defn ingest-files
  ([paths] (ingest-files paths {}))
  ([paths {:keys [attesting-did source-policy-cid method-note-cid]
           :or {attesting-did attesting-did-default
                source-policy-cid default-source-policy-cid
                method-note-cid default-method-note-cid}}]
   (when (empty? paths)
     (throw (ex-info "at least one reviewed platform export path is required" {})))
   (let [outputs (mapv #(parser/parse-platform-ad-library-fixture
                         (load-json %)
                         {:attesting-did attesting-did
                          :source-policy-cid source-policy-cid
                          :method-note-cid method-note-cid})
                       paths)
         merged (apply dry-run/merge-outputs outputs)]
     (dry-run/validate-output merged)
     merged)))

(defn summarize [records paths]
  (let [counts (into (sorted-map)
                     (map (fn [[name value]]
                            [name (if (sequential? value) (count value) 1)])
                          records))
        platforms (->> (get records "adDisclosureSnapshot")
                       (keep #(get % "platform"))
                       distinct
                       sort
                       vec)]
    {"actor" "akashi"
     "mode" "reviewed-platform-export-ingest"
     "networkAccess" false
     "writes" false
     "inputFiles" (mapv str paths)
     "platforms" platforms
     "recordCounts" counts
     "totalRecords" (reduce + 0 (vals counts))}))

(defn- parse-args [args]
  (loop [xs args
         opts {}
         paths []]
    (if-let [x (first xs)]
      (case x
        "--source-policy-cid" (recur (nnext xs) (assoc opts :source-policy-cid (second xs)) paths)
        "--method-note-cid" (recur (nnext xs) (assoc opts :method-note-cid (second xs)) paths)
        "--attesting-did" (recur (nnext xs) (assoc opts :attesting-did (second xs)) paths)
        "--emit-records" (recur (rest xs) (assoc opts :emit-records true) paths)
        "--emit-edn" (recur (rest xs) (assoc opts :emit-edn true) paths)
        "--emit-datomic" (recur (rest xs) (assoc opts :emit-datomic true) paths)
        "--out" (recur (nnext xs) (assoc opts :out (second xs)) paths)
        (recur (rest xs) opts (conj paths x)))
      (assoc opts :paths paths))))

(defn -main [& args]
  (let [{:keys [paths emit-records emit-edn emit-datomic out] :as opts} (parse-args args)
        records (ingest-files paths opts)
        payload (cond
                  emit-datomic (export/records-to-datomic-edn records)
                  emit-edn (export/records-to-edn records)
                  emit-records (str (pr-str records) "\n")
                  :else (str (pr-str (summarize records paths)) "\n"))]
    (if out
      (do
        (when-let [parent (.getParentFile (java.io.File. ^String out))]
          (.mkdirs parent))
        (spit out payload))
      (print payload))))
