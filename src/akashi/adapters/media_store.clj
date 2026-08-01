(ns akashi.adapters.media-store
  "Content-addressed storage for source-disclosed ad images/video.

  Media bytes live under data/collection/media and are intended for git-annex;
  small EDN manifests containing hashes, CIDs and provenance remain in Git."
  (:require [akashi.adapters.http :as http]
            [akashi.cid :as cid]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.security MessageDigest)
           (java.nio.file Files StandardOpenOption)))

(defn sha256-hex [^bytes body]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") body)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn- content-type [headers]
  (some-> (get headers "content-type") (str/split #";") first str/lower-case str/trim))

(defn- extension [mime url]
  (or ({"image/jpeg" ".jpg" "image/png" ".png" "image/webp" ".webp"
        "image/gif" ".gif" "image/avif" ".avif" "video/mp4" ".mp4"
        "video/webm" ".webm" "application/json" ".json"} mime)
      (some->> (re-find #"(?i)(\.(?:jpg|jpeg|png|webp|gif|avif|mp4|webm))(?:\?|$)" url)
               second str/lower-case)
      ".bin"))

(defn- supported-media? [mime]
  (or (str/starts-with? (or mime "") "image/")
      (str/starts-with? (or mime "") "video/")))

(defn store-bytes!
  [root source-url {:keys [body headers fetched-at]}]
  (let [mime (content-type headers)]
    (when-not (supported-media? mime)
      (throw (ex-info "refusing non image/video media payload"
                      {:url source-url :content-type mime})))
    (let [digest (sha256-hex body)
          rel (str "media/sha256/" (subs digest 0 2) "/" digest (extension mime source-url))
          file (io/file root rel)]
      (.mkdirs (.getParentFile file))
      (when-not (.exists file)
        (Files/write (.toPath file) body
                     (into-array StandardOpenOption [StandardOpenOption/CREATE_NEW
                                                     StandardOpenOption/WRITE])))
      {:akashi.media/source-url source-url
       :akashi.media/path rel
       :akashi.media/sha256 digest
       :akashi.media/cidv1 (cid/cid body)
       :akashi.media/content-type mime
       :akashi.media/bytes (alength ^bytes body)
       :akashi.media/fetched-at fetched-at})))

(defn fetch-and-store!
  [root url {:keys [max-bytes fetched-at headers]
             :or {max-bytes (* 50 1024 1024) headers {}}}]
  (store-bytes! root url
                (assoc (http/get-bytes url {:max-bytes max-bytes :headers headers})
                       :fetched-at fetched-at)))

(defn extract-public-media-urls [html]
  (->> [(re-seq #"(?is)<meta[^>]+(?:property|name)=[\"'](?:og:image|twitter:image|og:video|twitter:player:stream)[\"'][^>]+content=[\"']([^\"']+)[\"']" (or html ""))
        (re-seq #"(?is)<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"'](?:og:image|twitter:image|og:video|twitter:player:stream)[\"']" (or html ""))]
       (mapcat identity)
       (map second)
       (filter #(re-find #"^https?://" %))
       distinct
       vec))
