(ns akashi.adapters.dry-run-fixtures
  "Loads local akashi fixtures, parses and validates emitted records against the
  akashi lexicons, and summarizes deterministic dry-run counts. NO network
  access. Paths are repo-relative (bb runs from the repo root)."
  (:require [cheshire.core :as json]
            [akashi.adapters.lexicon-shape-validator :as validator]
            [akashi.adapters.regulator-bulk-fixture-parser :as parser]
            [akashi.adapters.platform-ad-library-fixture-parser :as platform-parser]
            [akashi.adapters.edn-export :as export]))

(def ^:private ROOT "wire")
(def ^:private LEX "wire/lex")
(def ^:private REGULATOR-FIXTURE (str ROOT "/fixtures/regulator_bulk/sample.json"))
(def ^:private REGULATOR-MISSING-OPTIONAL-FIXTURE (str ROOT "/fixtures/regulator_bulk/missing_optional_fields.json"))
(def ^:private CLOSURE-FIXTURE (str ROOT "/fixtures/closure/sample.json"))
(def ^:private META-INSTAGRAM-FIXTURE (str ROOT "/fixtures/platform_ad_library/meta_instagram_sample.json"))
(def ^:private X-ADS-FIXTURE (str ROOT "/fixtures/platform_ad_library/x_ads_sample.json"))

(def ^:private ATTESTING-DID "did:web:akashi.etzhayyim.com")
(def ^:private SOURCE-POLICY-CID "cid:akashi:source-policy:dry-run")
(def ^:private METHOD-NOTE-CID "cid:akashi:method-note:dry-run")
(def ^:private PARSE-OPTS {:attesting-did ATTESTING-DID
                          :source-policy-cid SOURCE-POLICY-CID
                          :method-note-cid METHOD-NOTE-CID})

(defn- load* [path] (json/parse-string (slurp path)))

(defn merge-outputs
  "Port of _merge_outputs(*outputs): list-valued keys are concatenated; scalar keys keep the
  first-seen value (setdefault)."
  [& outputs]
  (reduce (fn [merged output]
            (reduce (fn [m [name value]]
                      (if (sequential? value)
                        (update m name (fnil into []) value)
                        (if (contains? m name) m (assoc m name value))))
                    merged output))
          {} outputs))

(defn summarize
  "Port of summarize(records): deterministic counts without exposing payload detail."
  [records]
  (let [counts (into (sorted-map)
                     (map (fn [[name value]] [name (if (sequential? value) (count value) 1)]) records))]
    {"actor" "akashi"
     "mode" "fixture-dry-run"
     "networkAccess" false
     "writes" false
     "lexiconNamespaces" (vec (keys counts))
     "recordCounts" counts
     "totalRecords" (reduce + 0 (vals counts))}))

(defn validate-output
  "Port of _validate_output: validate every output key's record(s) against its akashi lexicon."
  [output]
  (doseq [[name value] output]
    (let [lexicon (load* (str LEX "/" name ".json"))]
      (if (sequential? value)
        (validator/validate-records value lexicon)
        (validator/validate-record value lexicon)))))

(defn load-dry-run-records
  "Load, parse, merge, and validate all local akashi fixtures."
  []
  (let [parsed (parser/parse-regulator-bulk-fixture (load* REGULATOR-FIXTURE) PARSE-OPTS)
        missing-optional (parser/parse-regulator-bulk-fixture (load* REGULATOR-MISSING-OPTIONAL-FIXTURE) PARSE-OPTS)
        meta-instagram (platform-parser/parse-platform-ad-library-fixture (load* META-INSTAGRAM-FIXTURE) PARSE-OPTS)
        x-ads (platform-parser/parse-platform-ad-library-fixture (load* X-ADS-FIXTURE) PARSE-OPTS)
        closure (get (load* CLOSURE-FIXTURE) "records")
        output (merge-outputs parsed missing-optional meta-instagram x-ads closure)]
    (validate-output output)
    output))

(defn -main [& args]
  (let [records (load-dry-run-records)
        payload (cond
                  (some #{"--emit-datomic"} args) (export/records-to-datomic-edn records)
                  (some #{"--emit-edn"} args) (export/records-to-edn records)
                  (some #{"--emit-records"} args) (str (pr-str records) "\n")
                  :else (str (pr-str (summarize records)) "\n"))
        out (second (drop-while #(not= "--out" %) args))]
    (if out
      (do
        (when-let [parent (.getParentFile (java.io.File. ^String out))]
          (.mkdirs parent))
        (spit out payload))
      (print payload))))
