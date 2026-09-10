(ns akashi.adapters.public-page-scribe
  "Public-information scribe for ad-transparency pages/files.
  Reads public pages or operator-saved public files, preserves a raw EDN
  snapshot, and parses only source-disclosed/operator-supplied fields."
  (:require [akashi.adapters.dry-run-fixtures :as dry-run]
            [akashi.adapters.edn-export :as export]
            [akashi.adapters.platform-ad-library-fixture-parser :as parser]
            [akashi.adapters.regulator-bulk-fixture-parser :as canon]
            [babashka.http-client :as http]
            [clojure.string :as str]
            [akashi.cid :as cid]))

(def source-policy-cid "cid:akashi:source-policy:public-page-scribe-r1")
(def method-note-cid "cid:akashi:method-note:public-page-scribe-r1")
(def attesting-did "did:web:akashi.etzhayyim.com")

(defn now [] (str (java.time.Instant/now)))

(defn- sha256 [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes ^String (str s) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bs))))

(defn- domain [url]
  (try
    (str/lower-case (or (.getAuthority (java.net.URI. url)) ""))
    (catch Throwable _ "")))

(defn- first-match [re s]
  (some-> (re-find re (or s "")) second str/trim))

(defn extract-title [html]
  (or (first-match #"(?is)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']" html)
      (first-match #"(?is)<meta[^>]+name=[\"']title[\"'][^>]+content=[\"']([^\"']+)[\"']" html)
      (first-match #"(?is)<title[^>]*>(.*?)</title>" html)))

(defn extract-description [html]
  (or (first-match #"(?is)<meta[^>]+property=[\"']og:description[\"'][^>]+content=[\"']([^\"']+)[\"']" html)
      (first-match #"(?is)<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']+)[\"']" html)))

(defn scribe-text
  [body {:keys [url fetched-at http-status content-type]
         :or {url "file://operator-supplied-public-page"
              fetched-at (now)
              http-status 200
              content-type "text/html"}}]
  (let [body (str body)
        digest (sha256 body)]
    {:akashi.scribe/url url
     :akashi.scribe/fetched-at fetched-at
     :akashi.scribe/http-status http-status
     :akashi.scribe/content-type content-type
     :akashi.scribe/body-sha256 digest
     :akashi.scribe/body-cid (cid/cid body)
     :akashi.scribe/title (extract-title body)
     :akashi.scribe/description (extract-description body)
     :akashi.scribe/body body}))

(defn scribe-file [path opts]
  (scribe-text (slurp path) (merge {:url (str "file://" path)} opts)))

(defn scribe-url
  [url {:keys [http-fn] :as opts
        :or {http-fn http/get}}]
  (let [resp (http-fn url {:throw false
                           :as :string
                           :headers {"user-agent" "akashi-public-page-scribe/1.0"}})]
    (when-not (<= 200 (:status resp) 299)
      (throw (ex-info "public page scribe failed"
                      {:url url :status (:status resp) :body (subs (str (:body resp)) 0 (min 512 (count (str (:body resp)))))})))
    (scribe-text (:body resp)
                 (merge opts
                        {:url url
                         :http-status (:status resp)
                         :content-type (get-in resp [:headers "content-type"] "text/html")}))))

(defn snapshot->platform-payload
  [snapshot {:keys [platform advertiser source-record-id landing-url creative-text started-at ended-at
                    jurisdiction country disclosed-category]
             :or {platform "public-page"
                  jurisdiction "global"
                  disclosed-category "public-page-scribed"}}]
  (let [url (:akashi.scribe/url snapshot)
        body (:akashi.scribe/body snapshot)
        title (:akashi.scribe/title snapshot)
        description (:akashi.scribe/description snapshot)
        record-id (or source-record-id (str "scribe-" (subs (sha256 (str url body)) 0 16)))
        text (or creative-text description title "")]
    {"capturedAt" (:akashi.scribe/fetched-at snapshot)
     "source" {"platform" platform
               "sourceFamily" "social-ad-library"
               "sourceUrl" url
               "jurisdiction" jurisdiction
               "accessMode" "public-page"
               "collectionStatus" "allowed"}
     "records" [{"sourceRecordId" record-id
                 "sourceUrl" url
                 "advertiser" {"displayName" (or advertiser title (domain url))
                               "websiteDomain" (domain url)
                               "jurisdiction" jurisdiction
                               "verifiedStatus" "not-disclosed"}
                 "landingUrl" (or landing-url url)
                 "creativeText" text
                 "disclosedCategory" disclosed-category
                 "sourceIssuePoliticalFlag" "not-applicable"
                 "startedAt" started-at
                 "endedAt" ended-at
                 "status" "unknown"
                 "regionSummary" [(or country jurisdiction)]
                 "targetingSummary" {"scribeBodyCid" (:akashi.scribe/body-cid snapshot)
                                     "scribeBodySha256" (:akashi.scribe/body-sha256 snapshot)
                                     "parseMode" "public-page-scribe"}}]}))

(defn parse-snapshot [snapshot opts]
  (let [records (parser/parse-platform-ad-library-fixture
                 (snapshot->platform-payload snapshot opts)
                 {:attesting-did attesting-did
                  :source-policy-cid source-policy-cid
                  :method-note-cid method-note-cid})]
    (dry-run/validate-output records)
    records))

(defn- storage-manifest [artifact datomic raw records edn datomic-edn]
  {:akashi.storage/artifact artifact
   :akashi.storage/cidv1 (cid/cid edn)
   :akashi.storage/sha256 (sha256 edn)
   :akashi.storage/format "datomic-datascript-tx-edn"
   :akashi.storage/raw-scribe {:path raw}
   :akashi.storage/datomic {:path datomic
                            :cidv1 (cid/cid datomic-edn)
                            :sha256 (sha256 datomic-edn)
                            :format "datomic-schema-and-scalar-tx-edn"}
   :akashi.storage/records (reduce + 0 (map #(if (sequential? %) (count %) 1) (vals records)))
   :akashi.storage/git {:path artifact :status "materialized"}
   :akashi.storage/datalad {:path artifact :next "bb kotoba:annex save data/scribe"}
   :akashi.storage/kotoba-rad {:path artifact
                               :cidv1 (cid/cid edn)
                               :akashi.storage/identity-journal "80-data/kotoba-rad/akashi.identity.journal.edn"
                               :next "bb rad:add-holding akashi --apply"}})

(defn materialize!
  [snapshot records {:keys [out datomic raw manifest]
                     :or {out "data/scribe/akashi-public-page.scribed.tx.kotoba.edn"
                          datomic "data/scribe/akashi-public-page.scribed.datomic.edn"
                          raw "data/scribe/akashi-public-page.raw-scribe.edn"
                          manifest "data/scribe/akashi-public-page.storage-manifest.edn"}}]
  (let [edn (export/records-to-edn records)
        datomic-edn (export/records-to-datomic-edn records)
        payload (storage-manifest out datomic raw records edn datomic-edn)]
    (doseq [p [out datomic raw manifest]]
      (when-let [parent (.getParentFile (java.io.File. ^String p))]
        (.mkdirs parent)))
    (spit raw (str (pr-str snapshot) "\n"))
    (spit out edn)
    (spit datomic datomic-edn)
    (spit manifest (str (pr-str payload) "\n"))
    payload))

(defn- parse-args [args]
  (loop [xs args opts {}]
    (if-let [x (first xs)]
      (case x
        "--url" (recur (nnext xs) (assoc opts :url (second xs)))
        "--file" (recur (nnext xs) (assoc opts :file (second xs)))
        "--platform" (recur (nnext xs) (assoc opts :platform (second xs)))
        "--advertiser" (recur (nnext xs) (assoc opts :advertiser (second xs)))
        "--source-record-id" (recur (nnext xs) (assoc opts :source-record-id (second xs)))
        "--landing-url" (recur (nnext xs) (assoc opts :landing-url (second xs)))
        "--creative-text" (recur (nnext xs) (assoc opts :creative-text (second xs)))
        "--started-at" (recur (nnext xs) (assoc opts :started-at (second xs)))
        "--ended-at" (recur (nnext xs) (assoc opts :ended-at (second xs)))
        "--jurisdiction" (recur (nnext xs) (assoc opts :jurisdiction (second xs)))
        "--country" (recur (nnext xs) (assoc opts :country (second xs)))
        "--out" (recur (nnext xs) (assoc opts :out (second xs)))
        "--datomic" (recur (nnext xs) (assoc opts :datomic (second xs)))
        "--raw" (recur (nnext xs) (assoc opts :raw (second xs)))
        "--manifest" (recur (nnext xs) (assoc opts :manifest (second xs)))
        "--emit-edn" (recur (rest xs) (assoc opts :emit-edn true))
        "--emit-datomic" (recur (rest xs) (assoc opts :emit-datomic true))
        "--materialize" (recur (rest xs) (assoc opts :materialize true))
        (throw (ex-info (str "unknown argument " x) {:arg x})))
      opts)))

(defn -main [& args]
  (let [{:keys [url file emit-edn emit-datomic materialize] :as opts} (parse-args args)
        snapshot (cond
                   url (scribe-url url opts)
                   file (scribe-file file opts)
                   :else (throw (ex-info "--url or --file is required" {})))
        records (parse-snapshot snapshot opts)]
    (cond
      materialize (println (pr-str (materialize! snapshot records opts)))
      emit-datomic (print (export/records-to-datomic-edn records))
      emit-edn (print (export/records-to-edn records))
      :else (println (pr-str {:scribe (dissoc snapshot :akashi.scribe/body)
                              :records (reduce + 0 (map #(if (sequential? %) (count %) 1) (vals records)))})))))
