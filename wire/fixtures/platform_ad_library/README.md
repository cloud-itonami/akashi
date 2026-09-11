# platform_ad_library fixtures

Local fixtures for public social ad-library disclosure records. They model
source-disclosed records from Meta/Facebook/Instagram and X/Twitter after manual
source-policy review, but they are not fetched by any live cell.

- Source family: `social-ad-library`
- Parser: `/20-actors/akashi/adapters/platform_ad_library_fixture_parser.cljc`
- Output lexicons: `adDisclosureSnapshot`, `advertiserIdentity`,
  `creativeDisclosure`, `deliveryDisclosure`, `landingEvidence`, `methodNote`,
  `sourcePolicySnapshot`
- Boundary: fixture-only; no login, scraping, API call, platform page fetch, or
  anti-bot bypass
- EDN projection: `kbb -m akashi.adapters.dry-run-fixtures --emit-edn`

Files:

- `meta_instagram_sample.json` covers a Meta/Facebook/Instagram-style public
  disclosure record with source-disclosed spend, impression, media, and limited
  targeting summary.
- `x_ads_sample.json` covers an X/Twitter-style public disclosure record with
  omitted optional spend/media fields preserved as absent rather than inferred.
