(ns akashi.adapters.test-continuous-collector
  (:require [akashi.adapters.continuous-collector :as collector]
            [akashi.adapters.edn-export :as export]
            [akashi.adapters.edn-query :as query]
            [akashi.adapters.media-store :as media]
            [akashi.adapters.murakumo-install :as murakumo]
            [akashi.adapters.platform-ad-library-fixture-parser :as parser]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "akashi-collection-test"
                                      (make-array FileAttribute 0))))

(deftest media-is-content-addressed-and-queryable
  (let [root (temp-dir)
        body (.getBytes "not-a-real-png-but-stable-test-bytes" StandardCharsets/UTF_8)
        asset (media/store-bytes! root "https://ads.example/creative.png"
                                  {:body body
                                   :headers {"content-type" "image/png"}
                                   :fetched-at "2026-08-01T00:00:00Z"})
        payload {"capturedAt" "2026-08-01T00:00:00Z"
                 "source" {"platform" "meta"
                           "sourceFamily" "social-ad-library"
                           "sourceUrl" "https://ads.example/library"
                           "accessMode" "official-api"
                           "collectionStatus" "allowed"}
                 "records" [{"sourceRecordId" "ad-1"
                              "sourceUrl" "https://ads.example/ad-1"
                              "advertiser" {"displayName" "Example"}
                              "landingUrl" "https://example.test/"
                              "creativeText" "hello"
                              "media" {"cid" (:akashi.media/cidv1 asset)
                                       "sha256" (:akashi.media/sha256 asset)
                                       "paths" [(:akashi.media/path asset)]
                                       "cids" [(:akashi.media/cidv1 asset)]
                                       "sha256s" [(:akashi.media/sha256 asset)]
                                       "contentTypes" [(:akashi.media/content-type asset)]}}]}
        records (parser/parse-platform-ad-library-fixture
                 payload {:attesting-did collector/attesting-did
                          :source-policy-cid collector/source-policy-cid
                          :method-note-cid collector/method-note-cid})
        tx (export/records-to-tx-data records)
        datomic (export/records-to-datomic-bundle records)]
    (testing "bytes are stored below the SHA-256 content address"
      (is (.exists (java.io.File. root (:akashi.media/path asset))))
      (is (re-find #"^media/sha256/[0-9a-f]{2}/[0-9a-f]{64}\.png$"
                   (:akashi.media/path asset))))
    (testing "both Git tx EDN and Datomic scalar bundles retain media identity"
      (is (= [(:akashi.media/path asset)]
             (mapv :path (query/media-assets tx))))
      (is (= [(:akashi.media/sha256 asset)]
             (mapv :sha256 (query/media-assets (query/datomic-entities datomic))))))))

(deftest empty-cycle-materializes-queryable-catalog-without-network
  (let [root (temp-dir)
        summary (collector/run-once! {:output-root (.getPath root) :sources []})]
    (is (= 0 (get-in summary [:akashi.collection/catalog :records])))
    (is (= [] (query/load-tx-data (str (java.io.File. root "catalog.tx.kotoba.edn")))))
    (is (.exists (java.io.File. root "catalog.datomic.edn")))
    (is (not (.exists (java.io.File. root ".lock"))))))

(deftest scheduled-empty-cycle-is-a-noop
  (let [root (temp-dir)
        summary (collector/run-once! {:output-root (.getPath root)
                                      :skip-empty true
                                      :sources []}
                                     {:publish? true})]
    (is (= :noop (:akashi.collection/status summary)))
    (is (not (.exists (java.io.File. root "catalog.records.edn"))))))

(deftest manual-inbox-html-and-image-e2e-without-network
  (let [root (temp-dir)
        inbox (java.io.File. root "inbox")
        output (java.io.File. root "collection")
        _ (.mkdirs inbox)
        _ (spit (java.io.File. inbox "ad.html")
                "<html><head><title>Saved public ad</title></head><body>public evidence</body></html>")
        image-bytes (.getBytes "stable-manual-image" StandardCharsets/UTF_8)
        _ (Files/write (.toPath (java.io.File. inbox "creative.png")) image-bytes
                       (into-array java.nio.file.StandardOpenOption
                                   [java.nio.file.StandardOpenOption/CREATE_NEW
                                    java.nio.file.StandardOpenOption/WRITE]))
        _ (spit (java.io.File. inbox "capture-001.capture.edn")
                (pr-str {:capture/id "capture-001"
                         :capture/platform "linkedin"
                         :capture/source-url "https://www.linkedin.com/ad-library/detail/example"
                         :capture/captured-at "2026-08-01T00:00:00Z"
                         :capture/operator-attested true
                         :capture/rights-basis "test fixture owned by repository"
                         :capture/page "ad.html"
                         :capture/media [{:path "creative.png" :content-type "image/png"}]
                         :capture/advertiser "Example Advertiser"
                         :capture/creative-text "Saved public creative"
                         :capture/landing-url "https://example.test/landing"
                         :capture/country "JP"}))
        config {:output-root (.getPath output)
                :skip-empty true
                :inbox {:enabled true
                        :path (.getPath inbox)
                        :policy-source-id "public-ad-transparency-pages"
                        :policy-approval-file
                        (.getCanonicalPath
                         (java.io.File. "data/registry/source-policy-approval.public-page-scribe.edn"))}
                :sources []}
        first-run (collector/run-once! config)
        tx (query/load-tx-data (str (java.io.File. output "catalog.tx.kotoba.edn")))
        second-run (collector/run-once! config)]
    (is (= ["inbox-capture-001"] (:akashi.collection/source-ids first-run)))
    (is (= 1 (:akashi.collection/media-count first-run)))
    (is (= 1 (count (query/media-assets tx))))
    (is (= ["Example Advertiser"] (query/advertiser-names tx)))
    (is (.exists (java.io.File. output "inbox-state/capture-001.processed.edn")))
    (is (= :noop (:akashi.collection/status second-run)))))

(deftest catalog-merge-deduplicates-identical-records
  (is (= {"creativeDisclosure" [{"creativeTextSha256" "abc"}]}
         (collector/merge-record-catalog
          {"creativeDisclosure" [{"creativeTextSha256" "abc"}]}
          {"creativeDisclosure" [{"creativeTextSha256" "abc"}]}))))

(deftest live-network-source-fails-closed-without-policy-approval
  (let [error (try
                (collector/collect-source!
                 {:id "unapproved" :kind :public-page
                  :url "https://example.test/ad"}
                 (.getPath (temp-dir)) "2026-08-01T00:00:00Z" 1024)
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is error)
    (is (= "public-page" (:required-access-mode (ex-data error))))))

(deftest murakumo-launchd-template-is-renderable-without-secrets
  (let [template (slurp "deploy/com.murakumo.akashi-collector.plist.tmpl")
        plist (murakumo/render-plist template
                                      {:repo-dir "/srv/a&b/akashi"
                                       :clojure-bin "/opt/homebrew/bin/clojure"
                                      :python-bin "/usr/bin/python3"
                                      :tailscale-ip "100.64.0.1"
                                      :log-dir "/srv/log"
                                      :interval-seconds 21600})]
    (is (str/includes? plist "<integer>21600</integer>"))
    (is (str/includes? plist "/srv/a&amp;b/akashi"))
    (is (not (str/includes? plist "{{")))
    (is (not (str/includes? plist "ACCESS_TOKEN")))))

(deftest tailnet-public-data-template-is-renderable-and-resident
  (let [template (slurp "deploy/com.murakumo.akashi-public-data.plist.tmpl")
        plist (murakumo/render-plist template
                                     {:repo-dir "/srv/akashi"
                                      :clojure-bin "/opt/homebrew/bin/clojure"
                                      :python-bin "/usr/bin/python3"
                                      :tailscale-ip "100.64.0.1"
                                      :log-dir "/srv/log"
                                      :interval-seconds 21600})]
    (is (str/includes? plist "<key>KeepAlive</key><true/>"))
    (is (str/includes? plist "100.64.0.1"))
    (is (str/includes? plist "/srv/akashi/data/collection"))
    (is (not (str/includes? plist "{{")))))
