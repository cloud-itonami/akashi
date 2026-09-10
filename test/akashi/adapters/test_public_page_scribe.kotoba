(ns akashi.adapters.test-public-page-scribe
  (:require [akashi.adapters.edn-export :as export]
            [akashi.adapters.edn-query :as q]
            [akashi.adapters.public-page-scribe :as scribe]
            [clojure.test :refer [deftest is]]))

(def html
  "<!doctype html><html><head><title>Example Public Advertiser</title><meta name=\"description\" content=\"Public ad disclosure text\"></head><body>visible public page</body></html>")

(deftest scribe-text-preserves-raw-and-parses-metadata
  (let [snap (scribe/scribe-text html {:url "https://public.example/ad/1"
                                       :fetched-at "2026-07-10T00:00:00Z"})]
    (is (= "https://public.example/ad/1" (:akashi.scribe/url snap)))
    (is (= "Example Public Advertiser" (:akashi.scribe/title snap)))
    (is (= "Public ad disclosure text" (:akashi.scribe/description snap)))
    (is (re-find #"^bafkr" (:akashi.scribe/body-cid snap)))))

(deftest public-page-snapshot-normalizes-to-akashi-records
  (let [snap (scribe/scribe-text html {:url "https://public.example/ad/1"
                                       :fetched-at "2026-07-10T00:00:00Z"})
        records (scribe/parse-snapshot snap {:platform "meta"
                                             :jurisdiction "EU"
                                             :country "DE"})
        tx (export/records-to-tx-data records)]
    (is (= 7 (count tx)))
    (is (= {"meta" 1} (q/count-by-platform tx)))
    (is (= ["Example Public Advertiser"] (q/advertiser-names tx)))
    (is (= "public-page" (get-in records ["sourcePolicySnapshot" "accessMode"])))
    (is (= "allowed" (get-in records ["sourcePolicySnapshot" "collectionStatus"])))))

(deftest public-page-materialize-writes-raw-and-parsed-edn
  (let [tmp (.toFile (java.nio.file.Files/createTempDirectory "akashi-scribe" (make-array java.nio.file.attribute.FileAttribute 0)))
        snap (scribe/scribe-text html {:url "https://public.example/ad/1"
                                       :fetched-at "2026-07-10T00:00:00Z"})
        records (scribe/parse-snapshot snap {:platform "x" :advertiser "Example X"})
        payload (scribe/materialize! snap records
                                     {:out (.getPath (java.io.File. tmp "out.edn"))
                                      :datomic (.getPath (java.io.File. tmp "datomic.edn"))
                                      :raw (.getPath (java.io.File. tmp "raw.edn"))
                                      :manifest (.getPath (java.io.File. tmp "manifest.edn"))})]
    (is (= 7 (:akashi.storage/records payload)))
    (is (.exists (java.io.File. tmp "raw.edn")))
    (is (.exists (java.io.File. tmp "out.edn")))
    (is (.exists (java.io.File. tmp "datomic.edn")))))
