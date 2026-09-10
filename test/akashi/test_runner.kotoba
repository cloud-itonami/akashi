(ns akashi.test-runner
  (:require [clojure.java.io :as io]
            [clojure.test :as test]))

(defn- ns-name [file]
  (with-open [r (java.io.PushbackReader. (io/reader file))]
    (second (read r))))

(defn -main [& _]
  (let [files (->> (file-seq (io/file "test/akashi"))
                   (filter #(.isFile %))
                   (filter #(re-find #"\.clj(c)?$" (.getName %)))
                   (remove #(= "test_runner.clj" (.getName %)))
                   (sort-by str))
        namespaces (mapv ns-name files)]
    (doseq [file files] (load-file (str file)))
    (let [{:keys [fail error]} (apply test/run-tests namespaces)]
      (shutdown-agents)
      (when (pos? (+ fail error)) (System/exit 1)))))
