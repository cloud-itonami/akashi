(ns akashi.methods.test-charter-gates
  "akashi — constitutional-gate conformance tests (manifest + central lexicons).

  Substrate-native Clojure (clj + datomic first tier). akashi is an AD-TRANSPARENCY / disclosure
  observatory — it mirrors public ad-disclosure pages, never runs an ad network, never profiles a
  person, never adjudicates. Its G1–G13 discipline is declared in the manifest
  `constitutionalDiscipline` and encoded as const + mandatory-provenance fields across the 10
  central AT-Proto lexicons at wire/lex/. This suite pins them
  so a future cell wave cannot silently drift them:

    G2  source-provenance-mandatory — a disclosure snapshot requires sourceUrl + fetchedAt +
        payloadCid + payloadSha256 (every datom traces to a fetched, hashed source)
    G3  non-adjudicating — link / report / malak-candidate carry nonAdjudicatingNotice const true
    G5  no-target-lists / source-limited — deliveryDisclosure.sourceLimited const true
    G6  open-method — methodNote requires sourceCodeCid + limits; records cite a methodNoteCid
    G7  source-policy-required — a snapshot binds a sourcePolicy; the policy records accessMode
    G10 public-record-minimization — advertiserIdentity.nonInferred const true (no inferred PII)
    G13 malak-bridge-review — malakEvidenceCandidate is evidence only (reviewStatus + non-adjudicating)

  Reads manifest via cheshire + central lexicons via cheshire (string keys). It weakens no gate;
  it asserts them. Murakumo-only (G11) + transparent-force (G12) + no-ad-SDK (G8) are manifest-level."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.set :as set]
            [clojure.string :as str]
            [cheshire.core :as json]))

#?(:clj
   (do
     (def ^:private actor-dir (java.io.File. "."))
     (def ^:private lexdir
       (java.io.File. actor-dir "wire/lex"))
     (defn- lex [name]
       (json/parse-string (slurp (java.io.File. lexdir (str name ".json")))))
     (defn- manifest []
       (:actor/manifest (clojure.edn/read-string (slurp (java.io.File. actor-dir "manifest.edn")))))))

(defn- record-node [doc]
  (let [main (get-in doc ["defs" "main"])] (or (get main "record") main)))
(defn- required-of [doc] (set (get (record-node doc) "required")))
(defn- const-of [doc field] (get-in (record-node doc) ["properties" field "const"]))

;; ── G1–G13 discipline declared (values are "G<n> — …") ──
(deftest all-13-gates-declared
  (let [vals (vals (get (manifest) "constitutionalDiscipline"))
        nums (->> vals (keep #(second (re-matches #"G(\d+).*" (str %))))
                  (map #(Integer/parseInt %)) set)]
    (is (= (set (range 1 14)) nums) "manifest constitutionalDiscipline must cover G1–G13")))

;; ── G2 — source-provenance mandatory ──
(deftest g2-source-provenance-mandatory
  (let [s (required-of (lex "adDisclosureSnapshot"))]
    (doseq [f ["sourceUrl" "fetchedAt" "payloadCid" "payloadSha256"]]
      (is (contains? s f) (str "G2: adDisclosureSnapshot must require " f))))
  (is (contains? (required-of (lex "sourcePolicySnapshot")) "sourceUrl")
      "G2: sourcePolicySnapshot must require a sourceUrl"))

;; ── G3 — non-adjudicating ──
(deftest g3-non-adjudicating
  (doseq [n ["adDisclosureLink" "adTransparencyReport" "malakEvidenceCandidate"]]
    (is (= true (const-of (lex n) "nonAdjudicatingNotice"))
        (str "G3: " n ".nonAdjudicatingNotice const true"))))

;; ── G5 — no target lists / source-limited delivery disclosure ──
(deftest g5-source-limited
  (is (= true (const-of (lex "deliveryDisclosure") "sourceLimited"))
      "G5: deliveryDisclosure.sourceLimited const true (source-public only, no target list)"))

;; ── G6 — open method ──
(deftest g6-open-method
  (let [m (required-of (lex "methodNote"))]
    (is (contains? m "sourceCodeCid") "G6: methodNote must publish its sourceCodeCid")
    (is (contains? m "limits") "G6: methodNote must state its limits"))
  (is (contains? (required-of (lex "adDisclosureLink")) "methodNoteCid")
      "G6: a disclosure link must cite the method that produced it"))

;; ── G7 — source-policy required ──
(deftest g7-source-policy-required
  (is (contains? (required-of (lex "adDisclosureSnapshot")) "sourcePolicyCid")
      "G7: a snapshot must bind the sourcePolicy that permitted it")
  (let [p (required-of (lex "sourcePolicySnapshot"))]
    (is (contains? p "accessMode") "G7: source policy records the accessMode")
    (is (contains? p "collectionStatus") "G7: source policy records collectionStatus")))

;; ── G10 — public-record-minimization: identity is non-inferred ──
(deftest g10-non-inferred-identity
  (is (= true (const-of (lex "advertiserIdentity") "nonInferred"))
      "G10: advertiserIdentity.nonInferred const true (no inferred private data)"))

;; ── G13 — malak bridge: evidence only, never an accusation ──
(deftest g13-malak-evidence-only
  (let [m (required-of (lex "malakEvidenceCandidate"))]
    (is (contains? m "reviewStatus") "G13: malak candidate carries a reviewStatus (gated import)")
    (is (contains? m "nonAdjudicatingNotice") "G13: malak candidate is non-adjudicating evidence")))

#?(:clj
   (defn -main [& _]
     (let [r (run-tests 'akashi.methods.test-charter-gates)]
       (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))))
