(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])
(def files (filter #(.isFile %) (file-seq (io/file "."))))
(defn path [f] (str/replace-first (str f) #"^\./" ""))
(doseq [f files :when (str/ends-with? (.getName f) ".edn")]
  (edn/read-string (slurp f)))
(let [jsons (set (map #(str/replace (subs (path %) (count "wire/")) #"\.json$" "")
                      (filter #(and (str/starts-with? (path %) "wire/")
                                    (str/ends-with? (path %) ".json")) files)))
      edns (set (map #(str/replace (subs (path %) (count "data/")) #"\.edn$" "")
                     (filter #(and (str/starts-with? (path %) "data/")
                                   (str/ends-with? (path %) ".edn")) files)))
      forbidden (filter #(re-find #"(?i)(\.go|\.py|run_tests\.(sh|cljs))$" (path %)) files)
      misplaced (filter #(and (re-find #"\.(json|jsonld)$" (path %))
                              (not (str/starts-with? (path %) "wire/"))
                              (not (= (path %) ".well-known/did.json"))) files)]
  (assert (every? edns jsons) "wire JSON is missing canonical EDN")
  (assert (empty? forbidden) (str "deprecated language/shell files: " forbidden))
  (assert (empty? misplaced) (str "external formats outside wire/: " misplaced)))
(println "audit: ok")
