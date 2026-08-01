# akashi (証) — Public Ad-Disclosure Transparency Actor

This is the standalone actor repository. EDN under `manifest.edn` and `data/`
is canonical; JSON/JSON-LD interchange artifacts are isolated under `wire/`.
Runtime code is under `src/akashi/` and tests mirror it under `test/akashi/`.

**DID**: `did:web:akashi.etzhayyim.com`
**Namespace**: `com.etzhayyim.akashi.*`
**ADR**: ADR-2606022300 (R0 scaffold)
**Status**: R0 design scaffold (2026-06-02)

akashi is a kotoba-native actor for ingesting already-public advertising
transparency disclosures from platform ad libraries into a source-cited EAVT
graph.

It is the advertising-disclosure sibling of `danjo`: passive public records in,
non-adjudicating transparency records out.

## Scope

akashi stores public ad disclosure evidence as kotoba datoms:

- platform source policy snapshots
- advertiser identities as disclosed by source platforms
- creative text/media hashes
- delivery period, region, spend range and impression range where disclosed
- disclosed targeting summaries
- landing URL/domain/redirect/content hash evidence
- cross-platform factual links by source ID, advertiser, landing domain or
  creative hash

## Malak Boundary

akashi is not part of `malak`. malak is confidential CTI and agency-referral
infrastructure. akashi is public transparency infrastructure.

The only allowed bridge is `malakEvidenceCandidate`: a reviewed, source-cited,
non-adjudicating evidence package when a public ad disclosure intersects already
known phishing, malware, brand-abuse or fraud indicators.

## R0 Cells

These cells are present as gated scaffolds under
`/40-engine/kotoba/crates/kotoba-kotodama/cells/akashi_*`. Each one raises at import until
ADR-2606022300 R1 activation and source-policy review gates are attested.

| Cell | Phase | Output |
|---|---|---|
| `akashi_source_registry` | periodic | `sourcePolicySnapshot` |
| `akashi_disclosure_fetch` | periodic | `adDisclosureSnapshot` |
| `akashi_normalize_creative` | continuous | advertiser / creative / delivery datoms |
| `akashi_landing_evidence` | rate-limited | `landingEvidence` |
| `akashi_cross_platform_link` | continuous | `adDisclosureLink` |
| `akashi_transparency_report` | periodic | `adTransparencyReport` |
| `akashi_malak_evidence_bridge` | event-gated | `malakEvidenceCandidate` |

## R0 Coverage

R0 coverage remains source-policy gated. A bounded JVM collector, Git/Datomic
catalog, DataLad/git-annex media store, and Murakumo launchd residence are now
implemented; no production source is enabled until its target, credential, and
approval transaction are configured. Python is not an akashi adapter surface. See:

- `COVERAGE.md` — source / field coverage matrix
- `MATURITY.md` — maturity scorecard and R1 work list
- `wire/registry/source-catalog.seed.json` — planning seed for Meta, X, LinkedIn, LINE,
  Google/YouTube, TikTok, and regulator repositories
- `wire/registry/source-policy-reviews.seed.json` — data-driven review state; Meta/X
  public-page scribe path is enabled, other live collection remains disabled
- `wire/registry/source-policy-approval.schema.json` — future approval transaction
  shape with rollback-to-disabled requirement
- `src/akashi/adapters/regulator_bulk_fixture_parser.cljc` and `wire/fixtures/regulator_bulk/`
  — local fixture parser only; no live collection
- `src/akashi/adapters/platform_ad_library_fixture_parser.cljc` and
  `wire/fixtures/platform_ad_library/` — local Meta/Instagram and X ad-library
  fixture parser coverage; no live collection
- `src/akashi/adapters/ingest_platform_ad_library.cljc` — reviewed local export ingest CLI
  for Meta/Instagram/X-style public ad-library JSON snapshots; emits records,
  DataScript/kotoba tx EDN, or Datomic schema+scalar tx EDN without network
  access or writes unless `--out` is explicitly passed
- `src/akashi/adapters/public_page_scribe.cljc` — production public-information scribe for
  public pages and operator-saved public files; preserves raw scribe EDN and
  materializes parsed EDN under `data/scribe/` only when `--materialize` is passed
- `src/akashi/adapters/edn_export.cljc` — deterministic DataScript/kotoba EDN tx-data plus
  a Datomic schema/scalar-tx import bundle for validated akashi records;
  callers choose git, DataLad, or kotoba-git/kotoba-rad storage
- `src/akashi/adapters/edn_query.cljc` — fixture tx-data query helper for platform,
  advertiser, landing-domain, and count queries without a live DB; it also
  materializes the Datomic scalar tx bundle for the same queries
- `src/akashi/adapters/continuous_collector.clj` — one policy-gated collection
  cycle for approved public pages, Meta Ad Library API, and LinkedIn Ad Library
  API; stores raw responses, records, and content-addressed media
- `config/collection.edn` — disabled-by-default source targets and six-hour cadence
- `data/inbox/` + `config/manual-capture.example.edn` — offline manual-capture
  inbox for operator-saved HTML and media; Murakumo ingests it without scraping
- `deploy/murakumo-install-task.edn` — Murakumo task-plane installation of the
  resident collector on the durable `naphtali` pin+compute node
- `docs/continuous-collection.md` — activation, DataLad/annex publication, query,
  and operational runbook
- `src/akashi/adapters/persist_fixture_edn.cljc` — materializes the deterministic tx-data
  plus a storage manifest under `data/` for git/DataLad/kotoba-rad handoff
- `data/akashi-platform-ad-library.fixture.tx.kotoba.edn` — materialized fixture
  tx-data artifact, readable as DataScript/kotoba EDN
- `data/akashi-platform-ad-library.fixture.datomic.edn` — materialized Datomic
  import bundle with schema and scalar `:db/add` tx-data
- `data/akashi-platform-ad-library.storage-manifest.edn` — storage handoff
  manifest naming the git path, CIDv1, DataLad save command, and akashi
  kotoba-rad identity journal
- `wire/fixtures/closure/` — non-adjudicating link/report/malak-candidate closure
  fixtures
- `src/akashi/adapters/dry_run_fixtures.cljc` — local fixture dry-run CLI; no network access
  and no writes; `--emit-edn` prints validated EDN tx-data
- `wire/fixtures/dry_run/summary.golden.json` — dry-run summary regression fixture
- `/wire/lex/` — 10 lexicon skeletons

## Persistence Status

As of 2026-07-10, the materialized fixture EDN is committed and pushed to:

- GitHub: `https://github.com/etzhayyim/root`
- Radicle: `rad:z2kYxHLH4E6pJHksgzAkRm9ztFgjC`

The persisted akashi dataset is the fixture dataset only:

- `data/akashi-platform-ad-library.fixture.tx.kotoba.edn`
- `data/akashi-platform-ad-library.fixture.datomic.edn`
- `data/akashi-platform-ad-library.storage-manifest.edn`
- CIDv1:
  `bafkreihcflz5xuinkb7ixurqccmlwl3gknc74uwthamg6vbwzhbsnmtqb4`
- kotoba-rad journal:
  `/80-data/kotoba-rad/akashi.identity.journal.edn`

No production `data/collection/` capture has been materialized in this workspace
yet. The continuous path is deployable but deliberately has zero enabled sources
until reviewed targets and official API approvals/credentials are supplied.

## Immutable Gates

- passive public-source only
- source provenance mandatory
- non-adjudicating
- no political profiling
- no target lists
- open method notes
- ToS / robots / source-policy snapshot required
- no ad SDK or tracking pixels
- no commercial ad-intel product
- no private-person inference
- Murakumo-only inference
- transparent publication only
- malak bridge requires review

## Related

- `/90-docs/adr/2606022300-akashi-public-ad-disclosure-kotoba-actor-r0.md`
- `/90-docs/adr/2607100000-akashi-platform-ad-library-cljc-edn-ingest.md`
- `/20-actors/danjo/README.md`
- `/60-apps/etzhayyim-project-malak/CLAUDE.md`
