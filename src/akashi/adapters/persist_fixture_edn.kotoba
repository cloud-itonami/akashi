(ns akashi.adapters.persist-fixture-edn
  "Materialize akashi fixture EDN artifacts for git/DataLad/kotoba-rad storage."
  (:require [akashi.adapters.dry-run-fixtures :as dry-run]
            [akashi.adapters.edn-export :as export]
            [akashi.cid :as cid]))

(def default-out "data/akashi-platform-ad-library.fixture.tx.kotoba.edn")
(def default-datomic "data/akashi-platform-ad-library.fixture.datomic.edn")
(def default-manifest "data/akashi-platform-ad-library.storage-manifest.edn")

(defn- mkdir-parent [path]
  (when-let [parent (.getParentFile (java.io.File. ^String path))]
    (.mkdirs parent)))

(defn- sha256 [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes ^String s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bs))))

(defn materialize
  ([] (materialize {:out default-out :manifest default-manifest :datomic default-datomic}))
  ([{:keys [out manifest datomic]
     :or {out default-out manifest default-manifest datomic default-datomic}}]
   (let [records (dry-run/load-dry-run-records)
         edn (export/records-to-edn records)
         datomic-edn (export/records-to-datomic-edn records)
         payload {:akashi.storage/artifact out
                  :akashi.storage/cidv1 (cid/cid edn)
                  :akashi.storage/sha256 (sha256 edn)
                  :akashi.storage/format "datomic-datascript-tx-edn"
                  :akashi.storage/datomic {:path datomic
                                           :cidv1 (cid/cid datomic-edn)
                                           :sha256 (sha256 datomic-edn)
                                           :format "datomic-schema-and-scalar-tx-edn"}
                  :akashi.storage/records (reduce + 0 (map #(if (sequential? %) (count %) 1)
                                                           (vals records)))
                  :akashi.storage/git {:path out :status "materialized"}
                  :akashi.storage/datalad {:path out
                                           :next "bb kotoba:annex save data"}
                  :akashi.storage/kotoba-rad {:path out
                                              :cidv1 (cid/cid edn)
                                              :akashi.storage/identity-journal
                                              "80-data/kotoba-rad/akashi.identity.journal.edn"
                                              :next "bb rad:add-holding akashi --apply"}}]
     (mkdir-parent out)
     (spit out edn)
     (mkdir-parent datomic)
     (spit datomic datomic-edn)
     (mkdir-parent manifest)
     (spit manifest (str (pr-str payload) "\n"))
     payload)))

(defn- parse-args [args]
  (loop [xs args
         opts {}]
    (if-let [x (first xs)]
      (case x
        "--out" (recur (nnext xs) (assoc opts :out (second xs)))
        "--manifest" (recur (nnext xs) (assoc opts :manifest (second xs)))
        "--datomic" (recur (nnext xs) (assoc opts :datomic (second xs)))
        (throw (ex-info (str "unknown argument " x) {:arg x})))
      opts)))

(defn -main [& args]
  (println (pr-str (materialize (parse-args args)))))
