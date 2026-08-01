(ns akashi.adapters.continuous-collector
  "One bounded collection cycle for reviewed public ad-transparency sources.

  The scheduler is external (Murakumo launchd/task-plane). A cycle fetches only
  configured public pages or official APIs, content-addresses media, appends a
  Git-resident record catalog, and regenerates DataScript/kotoba and Datomic
  import EDN. No login, UI automation, proxy rotation or anti-bot bypass exists."
  (:require [akashi.adapters.dry-run-fixtures :as dry-run]
            [akashi.adapters.edn-export :as export]
            [akashi.adapters.http :as http]
            [akashi.adapters.manual-capture :as manual]
            [akashi.adapters.media-store :as media]
            [akashi.adapters.platform-ad-library-fixture-parser :as parser]
            [akashi.adapters.public-page-scribe :as scribe]
            [akashi.cid :as cid]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.nio.file Files StandardOpenOption)
           (java.time Instant)))

(def attesting-did "did:web:akashi.etzhayyim.com")
(def source-policy-cid "cid:akashi:source-policy:continuous-public-collection-r1")
(def method-note-cid "cid:akashi:method-note:continuous-public-collection-r1")

(def live-parse-opts
  {:attesting-did attesting-did
   :source-policy-cid source-policy-cid
   :method-note-cid method-note-cid
   :parser-version "continuous-public-collection-r1.0"
   :method-id "akashi.continuous-public-collection"
   :method-family "official-api-or-public-page"
   :limits ["configured public pages and approved official APIs only"
            "no login, UI automation, proxy rotation, anti-bot bypass, tracking pixels, or private data"
            "source-disclosed fields only; no inferred person or targeting profiles"]})

(def meta-fields
  ["id" "ad_creation_time" "ad_delivery_start_time" "ad_delivery_stop_time"
   "ad_snapshot_url" "page_id" "page_name" "publisher_platforms"
   "ad_creative_bodies" "ad_creative_link_captions"
   "ad_creative_link_descriptions" "ad_creative_link_titles" "languages"
   "spend" "impressions" "delivery_by_region" "demographic_distribution"
   "target_ages" "target_gender" "target_locations" "eu_total_reach"
   "beneficiary_payers" "bylines"])

(defn now [] (str (Instant/now)))

(defn- run-id [timestamp]
  (str/replace timestamp #"[:.]" "-"))

(defn- read-edn-if-exists [path default]
  (if (.exists (io/file path)) (edn/read-string (slurp path)) default))

(defn- write-edn! [path value]
  (let [f (io/file path)]
    (when-let [parent (.getParentFile f)] (.mkdirs parent))
    (spit f (str (pr-str value) "\n"))))

(defn- credential-command [args]
  (when (seq args)
    (let [pb (doto (ProcessBuilder. ^java.util.List (vec args))
               (.redirectErrorStream true))
          proc (.start pb)
          output (with-open [reader (io/reader (.getInputStream proc))]
                   (str/trim (slurp reader)))
          exit (.waitFor proc)]
      (when (and (zero? exit) (not (str/blank? output))) output))))

(defn- credential! [source]
  (let [env-name (:token-env source)
        value (or (some-> (and env-name (System/getenv env-name)) str/trim not-empty)
                  (credential-command (:token-command source)))]
    (when (str/blank? value)
      (throw (ex-info "required official API credential is absent"
                      {:source-id (:id source)
                       :missing-env env-name
                       :credential-command-configured (boolean (seq (:token-command source)))})))
    value))

(defn- first-value [m k]
  (let [v (get m k)] (if (sequential? v) (first v) v)))

(defn- join-copy [ad]
  (->> ["ad_creative_bodies" "ad_creative_link_titles" "ad_creative_link_descriptions"]
       (mapcat #(let [v (get ad %)] (if (sequential? v) v [v])))
       (remove str/blank?)
       distinct
       (str/join "\n\n")))

(defn- range-map [m]
  (when (map? m)
    (cond-> {}
      (some? (get m "lower_bound")) (assoc "lower" (parse-long (str (get m "lower_bound"))))
      (some? (get m "upper_bound")) (assoc "upper" (parse-long (str (get m "upper_bound"))))
      (some? (get m "currency")) (assoc "currency" (get m "currency")))))

(defn- meta-record [ad]
  (let [snapshot-url (get ad "ad_snapshot_url")
        regions (->> (get ad "delivery_by_region")
                     (keep #(or (get % "region") (get % "name"))) vec)]
    (cond->
     {"sourceRecordId" (str (get ad "id"))
      "sourceUrl" snapshot-url
      "advertiser" {"displayName" (or (get ad "page_name") "source-not-disclosed")
                    "platformAdvertiserId" (some-> (get ad "page_id") str)
                    "pageUrl" (when-let [id (get ad "page_id")] (str "https://www.facebook.com/" id))
                    "verifiedStatus" "source-disclosed"}
      "landingUrl" snapshot-url
      "creativeText" (join-copy ad)
      "language" (first-value ad "languages")
      "disclosedCategory" "source-disclosed-ad-library"
      "sourceIssuePoliticalFlag" "source-not-disclosed"
      "startedAt" (get ad "ad_delivery_start_time")
      "endedAt" (get ad "ad_delivery_stop_time")
      "status" (if (get ad "ad_delivery_stop_time") "inactive" "active")
      "regionSummary" regions
      "targetingSummary" (select-keys ad ["demographic_distribution" "target_ages"
                                           "target_gender" "target_locations"
                                           "publisher_platforms" "eu_total_reach"])}
      (seq (range-map (get ad "spend"))) (assoc "spendRange" (range-map (get ad "spend")))
      (seq (range-map (get ad "impressions"))) (assoc "impressionRange" (range-map (get ad "impressions"))))))

(defn- fetch-meta-pages [source]
  (let [token (credential! source)
        version (or (:api-version source) "v23.0")
        base (str "https://graph.facebook.com/" version "/ads_archive")
        params {:ad_type (or (:ad-type source) "ALL")
                :search_terms (:search-terms source)
                :ad_reached_countries (json/generate-string (:ad-reached-countries source))
                :fields (str/join "," meta-fields)
                :limit (or (:limit source) 100)}
        first-url (http/with-query base params)
        max-pages (or (:max-pages source) 10)]
    (loop [url first-url page 0 rows [] raw []]
      (when (>= page max-pages)
        (throw (ex-info "Meta pagination exceeded configured max-pages"
                        {:source-id (:id source) :max-pages max-pages})))
      (let [{payload :json} (http/get-json url {:headers {"authorization" (str "Bearer " token)}})
            rows' (into rows (get payload "data" []))
            next-url (get-in payload ["paging" "next"])
            raw' (conj raw (dissoc payload "paging"))]
        (if (str/blank? next-url)
          {:rows rows' :raw raw'}
          (recur next-url (inc page) rows' raw'))))))

(defn- url-values [value]
  (cond
    (map? value) (mapcat url-values (vals value))
    (sequential? value) (mapcat url-values value)
    (and (string? value) (re-find #"^https?://" value)) [value]
    :else []))

(defn- linkedin-record [ad]
  (let [id (or (get ad "adId") (get ad "id") (get ad "urn") (cid/cid (pr-str ad)))
        advertiser (or (get ad "advertiserName") (get ad "companyName")
                       (get-in ad ["advertiser" "name"]) "source-not-disclosed")
        preview-url (or (get ad "previewUrl") (get ad "adPreviewUrl")
                        (get ad "landingPageUrl") (get ad "destinationUrl"))
        text (or (get ad "text") (get ad "headline") (get ad "description") "")]
    {"sourceRecordId" (str id)
     "sourceUrl" (or preview-url "https://www.linkedin.com/ad-library/home")
     "advertiser" {"displayName" advertiser
                   "platformAdvertiserId" (some-> (or (get ad "advertiserId")
                                                       (get ad "accountOwnerId")) str)
                   "verifiedStatus" "source-disclosed"}
     "landingUrl" (or (get ad "landingPageUrl") (get ad "destinationUrl") preview-url
                       "https://www.linkedin.com/ad-library/home")
     "creativeText" text
     "disclosedCategory" (or (get ad "adFormat") "source-disclosed-ad-library")
     "sourceIssuePoliticalFlag" "not-applicable"
     "startedAt" (or (get ad "firstImpressionAt") (get ad "startDate"))
     "endedAt" (or (get ad "lastImpressionAt") (get ad "endDate"))
     "status" (if (true? (get ad "restricted")) "restricted" "unknown")
     "regionSummary" (vec (or (get ad "countries") []))
     "targetingSummary" (or (get ad "targetingParameters") {})
     "mediaUrls" (->> (url-values ad)
                      (filter #(re-find #"(?i)(image|video|media|licdn|\.jpe?g|\.png|\.webp|\.mp4)" %))
                      distinct vec)}))

(defn- platform-payload [source captured-at records]
  {"capturedAt" captured-at
   "source" {"id" (:id source)
             "platform" (:platform source)
             "sourceFamily" "social-ad-library"
             "sourceUrl" (:url source)
             "jurisdiction" (or (:jurisdiction source) "global")
             "accessMode" (if (#{:meta-ad-library-api :linkedin-ad-library-api} (:kind source))
                            "official-api" "public-page")
             "collectionStatus" "allowed"}
   "records" records})

(defn- fetch-linkedin [source captured-at]
  (let [token (credential! source)
        url (:url source)]
    (when (or (str/blank? url) (str/starts-with? url "REPLACE_"))
      (throw (ex-info "LinkedIn Ad Library API product URL is not configured"
                      {:source-id (:id source)})))
    (let [{payload :json} (http/get-json url {:headers {"authorization" (str "Bearer " token)
                                                        "linkedin-version" (or (:linkedin-version source) "202607")
                                                        "x-restli-protocol-version" "2.0.0"}})
          rows (or (get payload "records") (get payload "elements") (get payload "data") [])]
      {:payload (platform-payload source captured-at (mapv linkedin-record rows))
       :raw payload})))

(defn- media-map [assets]
  (when (seq assets)
    {"cid" (:akashi.media/cidv1 (first assets))
     "sha256" (:akashi.media/sha256 (first assets))
     "paths" (mapv :akashi.media/path assets)
     "cids" (mapv :akashi.media/cidv1 assets)
     "sha256s" (mapv :akashi.media/sha256 assets)
     "contentTypes" (mapv :akashi.media/content-type assets)}))

(defn- capture-media! [output-root captured-at max-bytes urls]
  (reduce
   (fn [{:keys [assets errors]} url]
     (try
       {:assets (conj assets (media/fetch-and-store! output-root url {:max-bytes max-bytes
                                                                      :fetched-at captured-at}))
        :errors errors}
       (catch Exception e
         {:assets assets
          :errors (conj errors {:url url :error (.getMessage e)})})))
   {:assets [] :errors []}
   (distinct urls)))

(defn- enrich-record-media! [output-root captured-at max-bytes record]
  (let [{:keys [assets errors]} (capture-media! output-root captured-at max-bytes (get record "mediaUrls" []))]
    {:record (cond-> (dissoc record "mediaUrls")
               (seq assets) (assoc "media" (media-map assets)))
     :assets assets
     :errors errors}))

(defn- snapshot-media-urls [source record]
  (if (and (:capture-snapshot-media source) (not (str/blank? (get record "sourceUrl"))))
    (try
      (-> (http/get-text (get record "sourceUrl")
                         {:max-bytes (or (:max-page-bytes source) (* 10 1024 1024))})
          :text
          media/extract-public-media-urls)
      (catch Exception _ []))
    []))

(defn- collect-public-page! [source output-root captured-at max-bytes]
  (let [snapshot (scribe/scribe-url (:url source) {:max-page-bytes (or (:max-page-bytes source) (* 10 1024 1024))})
        media-result (capture-media! output-root captured-at max-bytes
                                     (media/extract-public-media-urls (:akashi.scribe/body snapshot)))
        records (scribe/parse-snapshot snapshot
                                       (assoc source :media (media-map (:assets media-result))))]
    {:records records :raw snapshot :media (:assets media-result) :media-errors (:errors media-result)}))

(defn- collect-meta! [source output-root captured-at max-bytes]
  (let [{:keys [rows raw]} (fetch-meta-pages source)
        base-records (mapv meta-record rows)
        enriched (mapv #(enrich-record-media!
                          output-root captured-at max-bytes
                          (assoc % "mediaUrls" (vec (concat (:media-urls source)
                                                            (snapshot-media-urls source %)))))
                       base-records)
        payload (platform-payload (assoc source :url "https://www.facebook.com/ads/library/")
                                  captured-at (mapv :record enriched))
        records (parser/parse-platform-ad-library-fixture
                 payload (assoc live-parse-opts :source-policy-cid (:policy-approval-cid source)))]
    (dry-run/validate-output records)
    {:records records :raw raw
     :media (vec (mapcat :assets enriched))
     :media-errors (vec (mapcat :errors enriched))}))

(defn- collect-linkedin! [source output-root captured-at max-bytes]
  (let [{:keys [payload raw]} (fetch-linkedin source captured-at)
        enriched (mapv #(enrich-record-media! output-root captured-at max-bytes %)
                       (get payload "records"))
        payload' (assoc payload "records" (mapv :record enriched))
        records (parser/parse-platform-ad-library-fixture
                 payload' (assoc live-parse-opts :source-policy-cid (:policy-approval-cid source)))]
    (dry-run/validate-output records)
    {:records records :raw raw
     :media (vec (mapcat :assets enriched))
     :media-errors (vec (mapcat :errors enriched))}))

(defn- source-access-mode [source]
  (if (contains? #{:public-page :manual-capture-inbox} (:kind source))
    "public-page"
    "official-api"))

(defn approved-policy-cid [source]
  (let [path (:policy-approval-file source)]
    (when (str/blank? path)
      (throw (ex-info "live source has no attested policy approval transaction"
                      {:source-id (:id source) :required-access-mode (source-access-mode source)})))
    (let [approval (read-edn-if-exists path nil)
          mode (source-access-mode source)]
      (when-not (and (= "allowed" (get approval "decision"))
                     (= "live-adapter" (get approval "runtime"))
                     (some #{mode} (get approval "approvedAccessModes"))
                     (= (:policy-source-id source) (get approval "sourceId")))
        (throw (ex-info "source policy approval does not authorize this live adapter"
                        {:source-id (:id source)
                         :policy-approval-file path
                         :required-access-mode mode})))
      (cid/cid (slurp path)))))

(defn collect-source! [source output-root captured-at max-bytes]
  (let [approved-source (assoc source :policy-approval-cid (approved-policy-cid source))]
    (case (:kind approved-source)
      :public-page (collect-public-page! approved-source output-root captured-at max-bytes)
      :meta-ad-library-api (collect-meta! approved-source output-root captured-at max-bytes)
      :linkedin-ad-library-api (collect-linkedin! approved-source output-root captured-at max-bytes)
      (throw (ex-info "unsupported reviewed collection source kind"
                      {:source-id (:id source) :kind (:kind source)})))))

(defn- value-items [v] (if (sequential? v) v [v]))

(defn merge-record-catalog [catalog records]
  (reduce
   (fn [out [family value]]
     (assoc out family (->> (concat (value-items (get out family [])) (value-items value))
                            (remove nil?) distinct vec)))
   (or catalog {}) records))

(defn- persist-source-run! [output-root run source result]
  (let [base (io/file output-root "runs" run (:id source))]
    (.mkdirs base)
    (write-edn! (io/file base "raw" "response.edn") (:raw result))
    (write-edn! (io/file base "records.tx.kotoba.edn")
                (export/records-to-tx-data (:records result)))
    (write-edn! (io/file base "records.datomic.edn")
                (export/records-to-datomic-bundle (:records result)))
    (write-edn! (io/file base "media-manifest.edn") (:media result))
    (write-edn! (io/file base "run-manifest.edn")
                {:akashi.run/source-id (:id source)
                 :akashi.run/platform (:platform source)
                 :akashi.run/kind (:kind source)
                 :akashi.run/media-count (count (:media result))
                 :akashi.run/media-errors (:media-errors result)
                 :akashi.run/record-count (count (export/records-to-tx-data (:records result)))})))

(defn- persist-catalog! [output-root catalog captured-at]
  (let [records-path (io/file output-root "catalog.records.edn")
        tx-path (io/file output-root "catalog.tx.kotoba.edn")
        datomic-path (io/file output-root "catalog.datomic.edn")
        tx (export/records-to-tx-data catalog)
        datomic (export/records-to-datomic-bundle catalog)]
    (write-edn! records-path catalog)
    (write-edn! tx-path tx)
    (write-edn! datomic-path datomic)
    (write-edn! (io/file output-root "catalog.manifest.edn")
                {:akashi.catalog/updated-at captured-at
                 :akashi.catalog/records (count tx)
                 :akashi.catalog/tx-cidv1 (cid/cid (pr-str tx))
                 :akashi.catalog/datomic-cidv1 (cid/cid (pr-str datomic))
                 :akashi.catalog/media-root "media/sha256"})
    {:records (count tx) :tx-cidv1 (cid/cid (pr-str tx))}))

(defn- run-command! [args]
  (let [pb (doto (ProcessBuilder. ^java.util.List (vec args)) (.inheritIO))
        proc (.start pb)
        exit (.waitFor proc)]
    (when-not (zero? exit)
      (throw (ex-info "persistence command failed" {:args (vec args) :exit exit})))
    exit))

(defn publish! [{:keys [datalad-save push remote branch annex-remote]
                 :or {remote "origin" branch "main"}} captured-at]
  (when datalad-save
    ;; Idempotent for an existing annex and necessary before DataLad can place
    ;; large/raw files under git-annex according to .gitattributes.
    (run-command! ["git" "annex" "init" "akashi-public-ad-media"])
    (run-command! ["datalad" "save" "-m" (str "data(akashi): collect public ads " captured-at)
                   "data/collection"])
    (when annex-remote
      (run-command! ["git" "annex" "copy" "data/collection/media"
                     "data/collection/runs" "--to" annex-remote])))
  (when push
    (run-command! ["git" "push" remote (str "HEAD:" branch)])))

(defn run-once!
  ([config] (run-once! config {}))
  ([config {:keys [publish?]}]
   (let [captured-at (now)
         run (run-id captured-at)
         output-root (or (:output-root config) "data/collection")
         max-bytes (or (:max-media-bytes config) (* 50 1024 1024))
         network-jobs (mapv (fn [source] {:source source}) (filterv :enabled (:sources config)))
         inbox-config (:inbox config)
         inbox-jobs (if (:enabled inbox-config)
                      (mapv (fn [capture]
                              {:source (manual/capture-source inbox-config capture)
                               :capture capture})
                            (manual/pending-captures inbox-config output-root))
                      [])
         jobs (into network-jobs inbox-jobs)]
     (if (and (:skip-empty config) (empty? jobs))
       {:akashi.collection/run-id run
        :akashi.collection/captured-at captured-at
        :akashi.collection/status :noop
        :akashi.collection/source-ids []
        :akashi.collection/media-count 0}
       (let [results
             (mapv (fn [{:keys [source capture]}]
                     (try
                       (let [result (if capture
                                      (manual/collect-capture!
                                       inbox-config output-root (approved-policy-cid source)
                                       max-bytes capture)
                                      (collect-source! source output-root captured-at max-bytes))]
                         (persist-source-run! output-root run source result)
                         (when capture
                           (manual/mark-processed! output-root (:capture-id capture) captured-at result))
                         {:source source :result result :status :ok})
                       (catch Exception e
                         {:source source :status :error :error (.getMessage e)
                          :data (ex-data e)})))
                   jobs)
             successful (filter #(= :ok (:status %)) results)
             existing (read-edn-if-exists (io/file output-root "catalog.records.edn") {})
             catalog (reduce #(merge-record-catalog %1 (get-in %2 [:result :records])) existing successful)
             catalog-result (persist-catalog! output-root catalog captured-at)
             summary {:akashi.collection/run-id run
                      :akashi.collection/captured-at captured-at
                      :akashi.collection/status :completed
                      :akashi.collection/sources (mapv #(select-keys % [:status :error :data]) results)
                      :akashi.collection/source-ids (mapv #(get-in % [:source :id]) results)
                      :akashi.collection/catalog catalog-result
                      :akashi.collection/media-count
                      (reduce + 0 (map #(count (get-in % [:result :media])) successful))}]
         (write-edn! (io/file output-root "last-run.edn") summary)
         (when publish? (publish! (:git config) captured-at))
         summary)))))

(defn- acquire-lock! [output-root]
  (let [file (io/file output-root ".lock")]
    (.mkdirs (.getParentFile file))
    (try
      (Files/writeString (.toPath file) (str (java.lang.ProcessHandle/current) "\n")
                         (into-array StandardOpenOption [StandardOpenOption/CREATE_NEW
                                                         StandardOpenOption/WRITE]))
      file
      (catch java.nio.file.FileAlreadyExistsException _
        (throw (ex-info "another akashi collection cycle holds the lock" {:lock (.getPath file)}))))))

(defn -main [& args]
  (let [config-path (or (second (drop-while #(not= "--config" %) args)) "config/collection.edn")
        config (cond-> (edn/read-string (slurp config-path))
                 (some #{"--inbox-only"} args) (assoc :sources []))
        publish? (boolean (some #{"--publish"} args))
        lock (acquire-lock! (or (:output-root config) "data/collection"))
        exit-code
        (try
          (let [summary (run-once! config {:publish? publish?})]
            (println (pr-str summary))
            (if (some #(= :error (:status %)) (:akashi.collection/sources summary)) 1 0))
          (finally
            (Files/deleteIfExists (.toPath lock))))]
    (when (pos? exit-code)
      (System/exit exit-code))))
