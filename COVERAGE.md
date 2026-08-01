# akashi 証 — Coverage Matrix

Honest R0 coverage for public ad-disclosure sources. **R0 ships source planning
and schema coverage, not live collection.** A source counts as covered only when
it has:

1. `sourcePolicySnapshot` with `collectionStatus=allowed`
2. a methodNote for its adapter/parser
3. at least one fixture, public page, or public file sample parsed into
   `adDisclosureSnapshot`
4. source lineage preserved through creative/delivery/landing records

## Source Coverage Status

| Source family | Platforms | R0 status | Counts as covered? |
|---|---|---|---|
| social ad libraries | Meta/Facebook/Instagram, X/Twitter | public-page scribe ready; fixture parser still covered | public page/file only |
| social ad libraries | LinkedIn | official API adapter implemented; product approval, endpoint, credential, and source-policy approval still required | no |
| social ad libraries | TikTok | registry seed only; no adapter | no |
| messaging / portal ad disclosures | LINE | registry seed only; access-mode requires manual review | no |
| search/video ad libraries | Google / YouTube | registry seed only; no adapter | no |
| regulator repositories | EU / DSA-style repositories, election-ad archives | fixture-only bulk parser with lexicon validation; no live adapter | no |
| regional transparency portals | jurisdiction-specific ad libraries | placeholder only | no |

## Field Coverage

| Field group | Lexicon | R0 schema? | Live data? |
|---|---|---|---|
| source policy / ToS / robots / cadence | `sourcePolicySnapshot` | yes | no |
| raw public snapshot + payload CID/hash | `adDisclosureSnapshot` | yes | no |
| disclosed advertiser identity | `advertiserIdentity` | yes | no |
| creative text/media/category | `creativeDisclosure` | yes | no |
| delivery period, spend/impression ranges | `deliveryDisclosure` | yes | no |
| landing URL/domain/hash evidence | `landingEvidence` | yes | no |
| non-adjudicating cross-platform links | `adDisclosureLink` | yes | no |
| aggregate transparency report | `adTransparencyReport` | yes | no |
| malak evidence candidate | `malakEvidenceCandidate` | yes | no |

Closure fixture coverage exists for `adDisclosureLink`, `adTransparencyReport`,
and `malakEvidenceCandidate`; all remain fixture-only and non-adjudicating.
Meta/Instagram and X fixture parser coverage exists for source-disclosed
platform ad-library records and projects them into the same advertiser,
creative, landing, delivery, and snapshot lexicons as regulator bulk fixtures.
`src/akashi/adapters/ingest_platform_ad_library.cljc` accepts reviewed local
Meta/Instagram/X-style JSON snapshots from an operator and emits records,
DataScript/kotoba tx EDN, or a Datomic schema/scalar-tx import bundle without
network access.
`src/akashi/adapters/public_page_scribe.cljc` and
`src/akashi/adapters/continuous_collector.clj` are the production paths for public
information: public pages and operator-saved public files are preserved as raw
scribe EDN, then parsed into DataScript/kotoba tx EDN and Datomic scalar bundle
EDN. The continuous collector additionally supports official Meta and LinkedIn
API responses, but its API sources fail closed without a dedicated approval
transaction and are disabled in committed configuration.
`src/akashi/adapters/dry_run_fixtures.cljc` exercises the local fixture set and validates
every emitted record without network access or writes.
`src/akashi/adapters/manual_capture.clj` ingests operator-saved public HTML and
image/video evidence from `data/inbox/` without contacting a platform. Each
sidecar requires an operator rights/terms attestation and is deduplicated by a
Git-resident processed marker.
`wire/fixtures/dry_run/summary.golden.json` pins the dry-run record counts. A second
regulator fixture covers missing optional source-disclosed fields, and negative
fixtures prove malformed source records / malak-imported closure records are
rejected.

## R1 Promotion Rules

A platform source can move from `candidate` to `covered-r1` only when:

- terms/robots/source policy is reviewed and represented as a
  `sourcePolicySnapshot`
- collection requires no login, no sockpuppet account, no anti-bot bypass, and
  no interactive dark-pattern path
- fixture parsing preserves source-limited fields without inventing missing
  values
- `methodNote` states false-positive limits
- `akashi` tests prove no voter/person profile fields are present in output
- malak bridge remains disabled unless a reviewed public IOC fixture is present
- `source-policy-reviews.seed.json` moves the source runtime from `disabled`
  with an attested review transaction
- `source-policy-approval.schema.json` records the review transaction and
  rollback-to-disabled requirements

## R0 Gaps

- Official API adapter code exists for Meta and LinkedIn, but neither source is
  activated or counted as covered without product access, credential, target,
  and an `official-api` policy approval transaction.
- Regulator bulk and platform ad-library fixture parsers validate output against
  akashi lexicons; no live platform adapter exists.
- Closure fixtures validate link/report/malak candidate records without live
  collection.
- Dry-run CLI exists for local fixture validation only; it has no network mode
  and does not write kotoba records. `--emit-edn` emits deterministic
  DataScript/kotoba tx-data plus a Datomic schema/scalar-tx import bundle for
  external storage/import by a caller.
- Dry-run summary has a golden regression fixture.
- Optional-field and negative fixtures exist for parser regression coverage.
- No live continuous-collection fetch has been materialized in this workspace.
- The API-free manual-capture inbox is operational, but no operator capture has
  yet been placed in the committed workspace.
- Fixture EDN and its storage manifest are durably published through GitHub and
  Radicle RID `rad:z2kYxHLH4E6pJHksgzAkRm9ztFgjC`; this is publication
  coverage for the reviewed fixture dataset, not live source coverage.
- Source-policy review workflow enables only the public-page scribe path for Meta/X;
  other live sources remain disabled.
- Source-policy approval format exists; current parser examples are
  fixture-only, not live collection.
- Cell scaffold exists under `40-engine/kotoba/crates/kotoba-kotodama/cells/akashi_*`, but every
  cell raises at import until ADR-2606022300 R1 activation gates are attested.
- Lexicon-specific invariant and fixture parser tests exist in
  `test/akashi/adapters/test_*.cljc` and `test/akashi/methods/test_manifest_invariants.cljc`.
- `source-catalog.seed.json` is planning metadata only; it does not authorize
  collection.
