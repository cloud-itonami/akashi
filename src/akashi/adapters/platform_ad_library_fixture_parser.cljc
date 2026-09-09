(ns akashi.adapters.platform-ad-library-fixture-parser
  "Fixture-only parser for local reviewed public platform ad-library snapshots.
  Performs no live fetching."
  (:require [clojure.string :as str]))

(def PARSER-VERSION "platform-ad-library-fixture-r1.0")
(def SOURCE-CODE-CID "cid:akashi:platform-ad-library-fixture-parser:r1")

(defn- esc-char [c]
  (let [n (int c)]
    (cond
      (= c \") "\\\""
      (= c \\) "\\\\"
      (= n 0x08) "\\b"
      (= n 0x0c) "\\f"
      (= c \newline) "\\n"
      (= c \return) "\\r"
      (= c \tab) "\\t"
      (< n 0x20) (format "\\u%04x" n)
      (> n 0x7f) (format "\\u%04x" n)
      :else (str c))))

(defn- canon-json [v]
  (cond
    (nil? v) "null"
    (boolean? v) (if v "true" "false")
    (integer? v) (str v)
    (number? v) (str v)
    (string? v) (str "\"" (apply str (map esc-char v)) "\"")
    (sequential? v) (str "[" (str/join "," (map canon-json v)) "]")
    (map? v) (str "{" (str/join "," (map (fn [k] (str (canon-json k) ":" (canon-json (get v k))))
                                         (sort (keys v)))) "}")
    :else (throw (ex-info (str "canon-json: unsupported " (type v)) {:v v}))))

(defn- sha256-hex [^String s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bs))))

(defn- sha256-json [value] (sha256-hex (canon-json value)))
(defn- cid [prefix value] (str "cid:akashi:" prefix ":" (subs (sha256-json value) 0 32)))

(defn- domain* [url]
  (let [auth (.getAuthority (java.net.URI. url))]
    (str/lower-case (or auth ""))))

(defn- drop-nils [m] (into {} (remove (comp nil? val) m)))

(defn- range* [sr]
  (when sr
    (drop-nils {"min" (get sr "min" (get sr "lower"))
                "max" (get sr "max" (get sr "upper"))
                "currency" (get sr "currency")
                "sourceLabel" (get sr "sourceLabel")})))

(defn parse-platform-ad-library-fixture
  [payload {:keys [attesting-did source-policy-cid method-note-cid]}]
  (let [source (get payload "source")
        records (get payload "records")
        captured-at (get payload "capturedAt")
        method-note {"createdAt" captured-at
                     "methodId" "akashi.platform-ad-library-fixture-parser"
                     "methodFamily" "snapshot-parser"
                     "version" PARSER-VERSION
                     "sourceCodeCid" SOURCE-CODE-CID
                     "limits" ["fixture-only parser; does not fetch remote ad libraries"
                               "requires a sourcePolicySnapshot before any live adapter is enabled"
                               "preserves source-disclosed fields and does not infer targeting profiles"]
                     "falsePositiveNotes" ["same page name or advertiser display name is not proof of common control"
                                           "source issue or political labels are mirrored, not adjudicated"]
                     "attestingDid" attesting-did}
        source-policy (drop-nils
                       {"createdAt" captured-at
                        "platform" (get source "platform")
                        "sourceFamily" (get source "sourceFamily")
                        "sourceUrl" (get source "sourceUrl")
                        "jurisdiction" (get source "jurisdiction")
                        "accessMode" (get source "accessMode" "manual-review-only")
                        "collectionStatus" (get source "collectionStatus" "manual-review")
                        "methodNoteCid" method-note-cid
                        "attestingDid" attesting-did})
        acc (reduce
             (fn [a sr]
               (let [snapshot {"fetchedAt" captured-at
                               "platform" (get source "platform")
                               "sourcePolicyCid" source-policy-cid
                               "sourceRecordId" (get sr "sourceRecordId")
                               "sourceUrl" (get sr "sourceUrl")
                               "payloadCid" (cid "payload" sr)
                               "payloadSha256" (sha256-json sr)
                               "parserVersion" PARSER-VERSION
                               "sourceLimited" true
                               "attestingDid" attesting-did}
                     snapshot-cid (cid "snapshot" snapshot)
                     adv (get sr "advertiser")
                     advertiser (drop-nils {"createdAt" captured-at
                                            "platform" (get source "platform")
                                            "sourceSnapshotCid" snapshot-cid
                                            "displayName" (get adv "displayName")
                                            "platformAdvertiserId" (get adv "platformAdvertiserId")
                                            "pageUrl" (get adv "pageUrl")
                                            "websiteDomain" (get adv "websiteDomain")
                                            "jurisdiction" (get adv "jurisdiction")
                                            "verifiedStatus" (get adv "verifiedStatus" "not-disclosed")
                                            "nonInferred" true
                                            "attestingDid" attesting-did})
                     advertiser-cid (cid "advertiser" advertiser)
                     landing-record {"fetchedAt" captured-at
                                     "sourceSnapshotCid" snapshot-cid
                                     "url" (get sr "landingUrl")
                                     "domain" (domain* (get sr "landingUrl"))
                                     "fetchMode" "manual-review-only"
                                     "methodNoteCid" method-note-cid
                                     "attestingDid" attesting-did}
                     landing-cid (cid "landing" landing-record)
                     creative-text (get sr "creativeText")
                     media (get sr "media")
                     creative-record (drop-nils
                                      {"createdAt" captured-at
                                       "sourceSnapshotCid" snapshot-cid
                                       "advertiserIdentityCid" advertiser-cid
                                       "creativeTextCid" (when creative-text (cid "creative-text" creative-text))
                                       "creativeTextSha256" (when creative-text (sha256-hex creative-text))
                                       "mediaCid" (get media "cid")
                                       "mediaSha256" (get media "sha256")
                                       "language" (get sr "language")
                                       "disclosedCategory" (get sr "disclosedCategory")
                                       "sourceIssuePoliticalFlag" (get sr "sourceIssuePoliticalFlag" "not-applicable")
                                       "landingEvidenceCid" landing-cid
                                       "methodNoteCid" method-note-cid
                                       "attestingDid" attesting-did})
                     creative-cid (cid "creative" creative-record)
                     targeting-summary (get sr "targetingSummary")
                     delivery-record (drop-nils
                                      {"createdAt" captured-at
                                       "sourceSnapshotCid" snapshot-cid
                                       "creativeDisclosureCid" creative-cid
                                       "startedAt" (get sr "startedAt")
                                       "endedAt" (get sr "endedAt")
                                       "status" (get sr "status" "unknown")
                                       "spendRange" (range* (get sr "spendRange"))
                                       "impressionRange" (range* (get sr "impressionRange"))
                                       "regionSummary" (get sr "regionSummary")
                                       "targetingSummaryCid" (when targeting-summary (cid "targeting-summary" targeting-summary))
                                       "sourceLimited" true
                                       "attestingDid" attesting-did})]
                 (-> a
                     (update "adDisclosureSnapshot" conj snapshot)
                     (update "advertiserIdentity" conj advertiser)
                     (update "landingEvidence" conj landing-record)
                     (update "creativeDisclosure" conj creative-record)
                     (update "deliveryDisclosure" conj delivery-record))))
             {"adDisclosureSnapshot" [] "advertiserIdentity" [] "landingEvidence" []
              "creativeDisclosure" [] "deliveryDisclosure" []}
             records)]
    (assoc acc "methodNote" method-note "sourcePolicySnapshot" source-policy)))
