(ns akashi.adapters.murakumo-install
  "Render and load akashi's per-user launchd residence on a Murakumo node."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.nio.file Files StandardOpenOption)
           (java.nio.file.attribute PosixFilePermission)))

(def label "com.murakumo.akashi-collector")

(defn- xml-escape [value]
  (str/escape (str value) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&apos;"}))

(defn render-plist [template {:keys [repo-dir clojure-bin log-dir interval-seconds]}]
  (reduce-kv str/replace template
             {"{{REPO_DIR}}" (xml-escape repo-dir)
              "{{CLOJURE_BIN}}" (xml-escape clojure-bin)
              "{{LOG_DIR}}" (xml-escape log-dir)
              "{{INTERVAL_SECONDS}}" (str interval-seconds)}))

(defn- command-output! [args]
  (let [proc (.start (doto (ProcessBuilder. ^java.util.List (vec args))
                       (.redirectErrorStream true)))
        output (with-open [reader (io/reader (.getInputStream proc))] (str/trim (slurp reader)))
        exit (.waitFor proc)]
    (when-not (zero? exit)
      (throw (ex-info "command failed" {:args (vec args) :exit exit :output output})))
    output))

(defn- run-command! [args tolerate-failure?]
  (try (command-output! args)
       (catch Exception e
         (when-not tolerate-failure? (throw e)))))

(defn- clojure-bin []
  (or (some #(when (.canExecute (io/file %)) %) ["/opt/homebrew/bin/clojure" "/usr/local/bin/clojure"])
      (throw (ex-info "clojure executable not found on Murakumo node" {}))))

(defn install! [repo-dir]
  (command-output! ["git" "annex" "version"])
  (command-output! ["datalad" "--version"])
  (let [repo (.getCanonicalPath (io/file repo-dir))
        home (System/getProperty "user.home")
        log-dir (io/file home ".akashi")
        agents-dir (io/file home "Library" "LaunchAgents")
        target (io/file agents-dir (str label ".plist"))
        config (edn/read-string (slurp (io/file repo "config" "collection.edn")))
        template (slurp (io/file repo "deploy" (str label ".plist.tmpl")))
        body (render-plist template {:repo-dir repo
                                     :clojure-bin (clojure-bin)
                                     :log-dir (.getCanonicalPath log-dir)
                                     :interval-seconds (or (:interval-seconds config) 21600)})
        uid (command-output! ["id" "-u"])
        domain (str "gui/" uid)]
    (.mkdirs log-dir)
    (.mkdirs agents-dir)
    (Files/writeString (.toPath target) body
                       (into-array StandardOpenOption [StandardOpenOption/CREATE
                                                       StandardOpenOption/TRUNCATE_EXISTING
                                                       StandardOpenOption/WRITE]))
    (try
      (Files/setPosixFilePermissions
       (.toPath target)
       #{PosixFilePermission/OWNER_READ PosixFilePermission/OWNER_WRITE})
      (catch UnsupportedOperationException _))
    (run-command! ["launchctl" "bootout" domain (str target)] true)
    (run-command! ["launchctl" "bootstrap" domain (str target)] false)
    (run-command! ["launchctl" "kickstart" "-k" (str domain "/" label)] false)
    {:label label :plist (str target) :domain domain :interval-seconds (:interval-seconds config)}))

(defn -main [& args]
  (let [repo (or (second (drop-while #(not= "--repo" %) args)) ".")]
    (println (pr-str (install! repo)))))
