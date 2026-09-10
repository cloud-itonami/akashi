(ns akashi.adapters.test-edn-query
  (:require [clojure.test :refer [deftest is]]
            [akashi.adapters.dry-run-fixtures :as dry-run]
            [akashi.adapters.edn-export :as export]
            [akashi.adapters.edn-query :as q]
            [akashi.adapters.ingest-platform-ad-library :as ingest]
            [akashi.adapters.persist-fixture-edn :as persist]))

(defn- fixture-db []
  (export/records-to-tx-data (dry-run/load-dry-run-records)))

(defn- fixture-datomic-bundle []
  (export/records-to-datomic-bundle (dry-run/load-dry-run-records)))

(deftest test-query-platform-ad-library-edn
  (let [db (fixture-db)]
    (is (= 25 (count (q/entities db))))
    (is (= {"meta" 1 "multi-platform" 2 "x" 1} (q/count-by-platform db)))
    (is (= 1 (count (q/by-platform db "meta"))))
    (is (= 1 (count (q/by-platform db "x"))))
    (is (some #{"Example Public Interest Project"} (q/advertiser-names db)))
    (is (some #{"Example Launch Account"} (q/advertiser-names db)))
    (is (some #{"example.org"} (q/landing-domains db)))
    (is (some #{"launch.example"} (q/landing-domains db)))))

(deftest test-query-facade
  (let [db (fixture-db)]
    (is (= ["Example Civic Notice Sponsor" "Example Launch Account"
            "Example Public Interest Project" "Minimal Public Disclosure Sponsor"]
           (q/query db {:op :advertisers})))
    (is (= {"meta" 1 "multi-platform" 2 "x" 1}
           (q/query db {:op :count-by-platform})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (q/query db {:op :unsupported})))))

(deftest test-datomic-bundle-import-shape-and-query
  (let [bundle (fixture-datomic-bundle)
        db (q/datomic-entities bundle)]
    (is (q/datomic-bundle-valid? bundle))
    (is (= 25 (count db)))
    (is (= {"meta" 1 "multi-platform" 2 "x" 1} (q/count-by-platform db)))
    (is (= 1 (count (q/by-platform db "meta"))))
    (is (some #{"Example Public Interest Project"} (q/advertiser-names db)))
    (is (some #{"launch.example"} (q/landing-domains db)))))

(deftest test-reviewed-platform-export-ingest-cljc
  (let [records (ingest/ingest-files
                 ["wire/fixtures/platform_ad_library/meta_instagram_sample.json"
                  "wire/fixtures/platform_ad_library/x_ads_sample.json"])
        summary (ingest/summarize records ["meta" "x"])]
    (is (= {"meta" 1 "x" 1} (q/count-by-platform (export/records-to-tx-data records))))
    (is (= ["meta" "x"] (get summary "platforms")))
    (is (= false (get summary "networkAccess")))
    (is (= false (get summary "writes")))))

(deftest test-persist-fixture-edn-materializes-storage-manifest
  (let [tmp (.toFile (java.nio.file.Files/createTempDirectory "akashi-edn" (make-array java.nio.file.attribute.FileAttribute 0)))
        out (.getPath (java.io.File. tmp "fixture.tx.edn"))
        datomic (.getPath (java.io.File. tmp "fixture.datomic.edn"))
        manifest (.getPath (java.io.File. tmp "manifest.edn"))
        payload (persist/materialize {:out out :datomic datomic :manifest manifest})]
    (is (.exists (java.io.File. out)))
    (is (.exists (java.io.File. datomic)))
    (is (.exists (java.io.File. manifest)))
    (is (= 25 (:akashi.storage/records payload)))
    (is (= "datomic-datascript-tx-edn" (:akashi.storage/format payload)))
    (is (re-find #"^bafkr" (:akashi.storage/cidv1 payload)))))
