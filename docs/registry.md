# akashi registry

Registry files are policy inputs for `akashi`; they do not authorize live
collection by themselves.

- `source-catalog.seed.json` lists candidate public disclosure source families.
- `source-policy-reviews.seed.json` records the R0/R1 review workflow state for
  each source. Meta/X public-page scribe paths are allowed; all other
  live collection remains disabled.
- `source-policy-approval.schema.json` defines the transaction shape for future
  source runtime changes.
- `source-policy-approval.fixture-only.example.json` is a non-live example that
  promotes only the local regulator fixture parser.
- `source-policy-approval.public-page-scribe.json` authorizes only public pages
  and operator-saved public files. It does not authorize platform-token collection,
  logged-in UI automation, or anti-bot bypass.
- The approval schema recognizes `official-api`, but no committed transaction
  currently authorizes it. Meta and LinkedIn API sources therefore fail closed.

The review registry is deliberately data-driven so an adapter can be disabled
without code changes.
