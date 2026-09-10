(ns akashi.adapters.test-dry-run-fixtures
  "Tests for the akashi dry-run fixtures orchestrator.
  Unit-tests the pure merge-outputs (list-extend + scalar first-wins) and summarize (sorted counts),
  then runs the full load → parse → merge → validate-against-real-lexicons pipeline and pins the
  deterministic summary (totalRecords 25 across 10 lexicon namespaces)."
  (:require [clojure.test :refer [deftest is]]
            [akashi.adapters.dry-run-fixtures :as d]))

(deftest test-merge-outputs
  (let [m (d/merge-outputs {"xs" [1] "scalar" {"k" 1}}
                           {"xs" [2 3] "scalar" {"k" 2}}   ; scalar already present → first wins
                           {"ys" [9]})]
    (is (= [1 2 3] (get m "xs")))            ; lists concatenated in order
    (is (= {"k" 1} (get m "scalar")))        ; setdefault: first value kept
    (is (= [9] (get m "ys")))))

(deftest test-summarize
  (let [s (d/summarize {"b" [1 2] "a" {"scalar" true} "c" [9 9 9]})]
    (is (= "akashi" (get s "actor")))
    (is (= "fixture-dry-run" (get s "mode")))
    (is (= false (get s "networkAccess")))
    (is (= false (get s "writes")))
    (is (= ["a" "b" "c"] (get s "lexiconNamespaces")))   ; sorted
    (is (= 1 (get-in s ["recordCounts" "a"])))           ; scalar → 1
    (is (= 2 (get-in s ["recordCounts" "b"])))
    (is (= 3 (get-in s ["recordCounts" "c"])))
    (is (= 6 (get s "totalRecords")))))

(deftest test-load-dry-run-records-golden
  ;; full pipeline: load fixtures → parse regulator/platform ones → merge w/ closure → validate vs real
  ;; lexicons (throws on any mismatch) → summarize. Pinned to the fixture contract.
  (let [s (d/summarize (d/load-dry-run-records))]
    (is (= 25 (get s "totalRecords")))
    (is (= ["adDisclosureLink" "adDisclosureSnapshot" "adTransparencyReport" "advertiserIdentity"
            "creativeDisclosure" "deliveryDisclosure" "landingEvidence" "malakEvidenceCandidate"
            "methodNote" "sourcePolicySnapshot"]
           (get s "lexiconNamespaces")))
    (is (= {"adDisclosureLink" 1 "adDisclosureSnapshot" 4 "adTransparencyReport" 1
            "advertiserIdentity" 4 "creativeDisclosure" 4 "deliveryDisclosure" 4
            "landingEvidence" 4 "malakEvidenceCandidate" 1 "methodNote" 1 "sourcePolicySnapshot" 1}
           (into {} (get s "recordCounts"))))
    (is (= false (get s "networkAccess")))))
