(ns akashi.adapters.manual-capture
  "Offline ingestion of operator-saved public ad evidence.

  A *.capture.edn sidecar names a saved HTML page and optional image/video
  files. Nothing in this namespace performs network access."
  (:require [akashi.adapters.media-store :as media]
            [akashi.adapters.public-page-scribe :as scribe]
            [akashi.cid :as cid]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files StandardOpenOption)
           (java.time Instant)))

(def allowed-platforms #{"meta" "facebook" "instagram" "linkedin" "x" "twitter"})
(def capture-id-pattern #"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

(defn- now [] (str (Instant/now)))

(defn- ensure-public-url! [url]
  (let [uri (try (java.net.URI. url) (catch Exception _ nil))]
    (when-not (and uri (contains? #{"http" "https"} (.getScheme uri)))
      (throw (ex-info "manual capture requires its original public http(s) URL"
                      {:source-url url})))
    url))

(defn- safe-child! [root relative-path]
  (let [root-file (.getCanonicalFile (io/file root))
        child (.getCanonicalFile (io/file root-file relative-path))
        prefix (str (.getPath root-file) java.io.File/separator)]
    (when-not (str/starts-with? (.getPath child) prefix)
      (throw (ex-info "capture path escapes the inbox" {:path relative-path})))
    (when-not (.isFile child)
      (throw (ex-info "capture input file is absent" {:path (.getPath child)})))
    child))

(defn- capture-id! [descriptor descriptor-file]
  (let [value (or (:capture/id descriptor)
                  (str/replace (.getName (io/file descriptor-file)) #"\.capture\.edn$" ""))]
    (when-not (re-matches capture-id-pattern (str value))
      (throw (ex-info "invalid capture id" {:capture-id value})))
    (str value)))

(defn- infer-content-type [file declared]
  (or declared
      (Files/probeContentType (.toPath file))
      (let [name (str/lower-case (.getName file))]
        (cond
          (str/ends-with? name ".jpg") "image/jpeg"
          (str/ends-with? name ".jpeg") "image/jpeg"
          (str/ends-with? name ".png") "image/png"
          (str/ends-with? name ".webp") "image/webp"
          (str/ends-with? name ".gif") "image/gif"
          (str/ends-with? name ".mp4") "video/mp4"
          (str/ends-with? name ".webm") "video/webm"
          :else "application/octet-stream"))))

(defn- media-entry [entry]
  (if (string? entry) {:path entry} entry))

(defn- read-file-bytes! [file max-bytes]
  (let [size (Files/size (.toPath file))]
    (when (> size max-bytes)
      (throw (ex-info "manual capture file exceeds configured byte limit"
                      {:path (.getPath file) :bytes size :max-bytes max-bytes})))
    (Files/readAllBytes (.toPath file))))

(defn- store-media! [inbox-root output-root captured-at max-bytes entry]
  (let [{:keys [path url] declared-content-type :content-type} (media-entry entry)
        file (safe-child! inbox-root path)
        body (read-file-bytes! file max-bytes)]
    (media/store-bytes! output-root (or url (str "file:inbox/" path))
                        {:body body
                         :headers {"content-type" (infer-content-type file declared-content-type)}
                         :fetched-at captured-at})))

(defn- media-map [assets]
  (when (seq assets)
    {"cid" (:akashi.media/cidv1 (first assets))
     "sha256" (:akashi.media/sha256 (first assets))
     "paths" (mapv :akashi.media/path assets)
     "cids" (mapv :akashi.media/cidv1 assets)
     "sha256s" (mapv :akashi.media/sha256 assets)
     "contentTypes" (mapv :akashi.media/content-type assets)}))

(defn- marker-file [output-root capture-id]
  (io/file output-root "inbox-state" (str capture-id ".processed.edn")))

(defn pending-captures
  [{:keys [path] :or {path "data/inbox"}} output-root]
  (let [root (io/file path)]
    (.mkdirs root)
    (->> (file-seq root)
         (filter #(.isFile %))
         (filter #(str/ends-with? (.getName %) ".capture.edn"))
         (mapv (fn [file]
                 (try
                   (let [descriptor (edn/read-string (slurp file))
                         capture-id (capture-id! descriptor file)]
                     {:capture-id capture-id
                      :descriptor descriptor
                      :descriptor-file (.getCanonicalPath file)})
                   (catch Exception e
                     {:capture-id (str "invalid-" (subs (cid/cid (.getCanonicalPath file)) 0 24))
                      :descriptor-file (.getCanonicalPath file)
                      :scan-error (.getMessage e)
                      :scan-data (ex-data e)}))))
         (remove #(.exists (marker-file output-root (:capture-id %))))
         vec)))

(defn capture-source [inbox-config {:keys [capture-id descriptor]}]
  {:id (str "inbox-" capture-id)
   :kind :manual-capture-inbox
   :platform (:capture/platform descriptor)
   :policy-source-id (:policy-source-id inbox-config)
   :policy-approval-file (:policy-approval-file inbox-config)})

(defn collect-capture!
  [{:keys [path] :or {path "data/inbox"}} output-root policy-cid max-bytes
   {:keys [capture-id descriptor descriptor-file scan-error scan-data]}]
  (when scan-error
    (throw (ex-info "invalid manual capture sidecar"
                    {:capture-id capture-id :descriptor-file descriptor-file
                     :scan-error scan-error :scan-data scan-data})))
  (let [platform (str/lower-case (str (:capture/platform descriptor)))
        _ (when-not (contains? allowed-platforms platform)
            (throw (ex-info "unsupported manual capture platform" {:platform platform})))
        source-url (ensure-public-url! (:capture/source-url descriptor))
        _ (when-not (and (true? (:capture/operator-attested descriptor))
                         (not (str/blank? (:capture/rights-basis descriptor))))
            (throw (ex-info "manual capture requires operator rights/terms attestation"
                            {:capture-id capture-id})))
        _ (when (and (str/blank? (:capture/page descriptor))
                     (str/blank? (:capture/creative-text descriptor)))
            (throw (ex-info "manual capture requires a saved page or creative text"
                            {:capture-id capture-id})))
        captured-at (or (:capture/captured-at descriptor) (now))
        html (if-let [page (:capture/page descriptor)]
               (String. (read-file-bytes! (safe-child! path page) max-bytes)
                        StandardCharsets/UTF_8)
               (str (:capture/creative-text descriptor "")))
        snapshot (scribe/scribe-text html {:url source-url :fetched-at captured-at})
        assets (mapv #(store-media! path output-root captured-at max-bytes %)
                     (or (:capture/media descriptor) []))
        records (scribe/parse-snapshot
                 snapshot
                 {:platform platform
                  :advertiser (:capture/advertiser descriptor)
                  :source-record-id (or (:capture/source-record-id descriptor) capture-id)
                  :landing-url (:capture/landing-url descriptor)
                  :creative-text (:capture/creative-text descriptor)
                  :started-at (:capture/started-at descriptor)
                  :ended-at (:capture/ended-at descriptor)
                  :jurisdiction (or (:capture/jurisdiction descriptor) "global")
                  :country (:capture/country descriptor)
                  :disclosed-category (:capture/disclosed-category descriptor)
                  :media (media-map assets)
                  :policy-approval-cid policy-cid})]
    {:records records
     :raw {:akashi.inbox/descriptor descriptor
           :akashi.inbox/descriptor-file descriptor-file
           :akashi.inbox/snapshot snapshot}
     :media assets
     :media-errors []}))

(defn mark-processed! [output-root capture-id captured-at result]
  (let [file (marker-file output-root capture-id)
        value {:akashi.inbox/capture-id capture-id
               :akashi.inbox/processed-at captured-at
               :akashi.inbox/descriptor-cidv1
               (cid/cid (pr-str (get-in result [:raw :akashi.inbox/descriptor])))
               :akashi.inbox/media-count (count (:media result))}]
    (.mkdirs (.getParentFile file))
    (Files/writeString (.toPath file) (str (pr-str value) "\n")
                       (into-array StandardOpenOption [StandardOpenOption/CREATE_NEW
                                                       StandardOpenOption/WRITE]))
    value))
