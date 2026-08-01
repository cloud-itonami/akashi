(ns akashi.adapters.collection-query
  "CLI over the Git-resident DataScript/kotoba tx catalog or Datomic bundle."
  (:require [akashi.adapters.edn-query :as q]
            [clojure.pprint :as pprint]))

(defn- parse-args [args]
  (loop [xs args opts {:file "data/collection/catalog.tx.kotoba.edn" :format :tx}]
    (if-let [x (first xs)]
      (case x
        "--file" (recur (nnext xs) (assoc opts :file (second xs)))
        "--datomic" (recur (nnext xs) (assoc opts :file (second xs) :format :datomic))
        "platform" (recur (nnext xs) (assoc opts :op :platform :platform (second xs)))
        "advertisers" (recur (rest xs) (assoc opts :op :advertisers))
        "landing-domains" (recur (rest xs) (assoc opts :op :landing-domains))
        "count-by-platform" (recur (rest xs) (assoc opts :op :count-by-platform))
        "media" (recur (rest xs) (assoc opts :op :media))
        (throw (ex-info "unknown query argument" {:arg x})))
      opts)))

(defn -main [& args]
  (let [{:keys [file format op platform]} (parse-args args)
        db (if (= format :datomic)
             (q/datomic-entities (q/load-datomic-bundle file))
             (q/load-tx-data file))]
    (when-not op
      (throw (ex-info "query is required: platform|advertisers|landing-domains|count-by-platform|media" {})))
    (pprint/pprint (q/query db {:op op :platform platform}))))
