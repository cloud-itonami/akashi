(ns akashi.adapters.http
  "Small JDK-only HTTP boundary with bounded response bodies."
  (:require [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.net URI URLEncoder)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)))

(def default-user-agent "akashi-public-ad-transparency-collector/1.0")
(def default-max-bytes (* 10 1024 1024))

(defonce ^HttpClient client
  (-> (HttpClient/newBuilder)
      (.followRedirects HttpClient$Redirect/NORMAL)
      (.connectTimeout (Duration/ofSeconds 20))
      .build))

(defn encode-query [params]
  (->> params
       (remove (comp nil? val))
       (mapcat (fn [[k v]]
                 (for [item (if (sequential? v) v [v])]
                   (str (URLEncoder/encode (name k) "UTF-8") "="
                        (URLEncoder/encode (str item) "UTF-8")))))
       (str/join "&")))

(defn with-query [url params]
  (let [q (encode-query params)]
    (if (str/blank? q) url (str url (if (str/includes? url "?") "&" "?") q))))

(defn- allowed-uri! [url]
  (let [uri (URI. url)]
    (when-not (contains? #{"http" "https"} (.getScheme uri))
      (throw (ex-info "only public http(s) collection is allowed" {:url url})))
    uri))

(defn- safe-url [url]
  (str/replace (str url) #"(?i)(access_token|token|key|secret)=[^&]+" "$1=REDACTED"))

(defn- read-bounded ^bytes [^InputStream in max-bytes]
  (with-open [in in
              out (ByteArrayOutputStream.)]
    (let [buf (byte-array 16384)]
      (loop [total 0]
        (let [n (.read in buf)]
          (if (neg? n)
            (.toByteArray out)
            (let [next-total (+ total n)]
              (when (> next-total max-bytes)
                (throw (ex-info "HTTP response exceeds configured byte limit"
                                {:max-bytes max-bytes :observed-at-least next-total})))
              (.write out buf 0 n)
              (recur next-total))))))))

(defn get-bytes
  ([url] (get-bytes url {}))
  ([url {:keys [headers max-bytes timeout-seconds]
         :or {headers {} max-bytes default-max-bytes timeout-seconds 60}}]
   (let [uri (allowed-uri! url)
         builder (doto (HttpRequest/newBuilder uri)
                   (.GET)
                   (.timeout (Duration/ofSeconds timeout-seconds))
                   (.header "user-agent" default-user-agent)
                   (.header "accept" "*/*"))]
     (doseq [[k v] headers]
       (.header builder (name k) (str v)))
     (let [resp (.send client (.build builder) (HttpResponse$BodyHandlers/ofInputStream))
           status (.statusCode resp)
           body (read-bounded (.body resp) max-bytes)
           response-headers (into {}
                                  (map (fn [[k values]]
                                         [(str/lower-case k) (str/join "," values)]))
                                  (.map (.headers resp)))]
       (when-not (<= 200 status 299)
         (throw (ex-info "public HTTP collection failed"
                         {:url (safe-url url) :status status
                          :body-preview (String. body 0 (min 512 (alength body)) StandardCharsets/UTF_8)})))
       {:url (str (.uri resp))
        :status status
        :headers response-headers
        :body body}))))

(defn get-text
  ([url] (get-text url {}))
  ([url opts]
   (let [resp (get-bytes url opts)]
     (assoc resp :text (String. ^bytes (:body resp) StandardCharsets/UTF_8)))))

(defn get-json
  ([url] (get-json url {}))
  ([url opts]
   (let [resp (get-text url opts)]
     (assoc resp :json (json/parse-string (:text resp))))))
