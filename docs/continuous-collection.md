# Continuous collection on Murakumo

The collector runs one bounded cycle every six hours. launchd supplies
residence and restart scheduling; Murakumo's task plane installs or updates
that residence. A second LaunchAgent serves `data/collection` on port 8042,
bound only to the host's Tailscale IPv4 address, and uses `KeepAlive` to recover
from process failure.

No source is enabled in the committed configuration. This is intentional: a
source must have a concrete reviewed target, a matching `allowed` approval EDN,
and—when using an official API—the provider's product access and credential.
The collector validates that approval before making a network request.

The manual-capture inbox is enabled independently. It performs no network
access and only processes operator-supplied files with an explicit rights/terms
attestation.

## API-free manual capture inbox

Save the public evidence from your browser, then place the HTML and media files
under `data/inbox/`. Copy `config/manual-capture.example.edn` into the same
directory using a unique `*.capture.edn` name and update its fields. Paths in
the sidecar are relative to `data/inbox/`.

Required provenance fields are:

- `:capture/id`, `:capture/platform`, and the original `:capture/source-url`
- `:capture/operator-attested true`
- a non-empty `:capture/rights-basis`
- at least a saved `:capture/page` or source-disclosed `:capture/creative-text`

Run an offline-only ingestion cycle with:

```bash
clojure -M:collect --config config/collection.edn --inbox-only
clojure -M:query media
```

Murakumo runs the same inbox check every six hours. Successful captures receive
`data/collection/inbox-state/<capture-id>.processed.edn`, so retained source
files are not imported twice. Source inbox files are ignored by Git; their raw
HTML is preserved in the annexed run response and media is copied into the
content-addressed media store.

## Activate a source

Edit `config/collection.edn` and set `:enabled true` only after replacing its
placeholder search/URL. Public-page sources use the existing public-page
approval. Official API sources additionally require a new approval transaction
whose `approvedAccessModes` includes `official-api`; point
`:policy-approval-file` and `:policy-source-id` at it.

Store official API tokens in the node's login Keychain (never Git):

```bash
security add-generic-password -U -s akashi-meta-ad-library -a akashi -w
security add-generic-password -U -s akashi-linkedin-ad-library -a akashi -w
```

Environment variables named in `:token-env` override Keychain lookup for a
manual one-shot run.

## Git, Datomic, and media

Each successful cycle writes:

- `data/collection/catalog.records.edn`: merge source of normalized records
- `data/collection/catalog.tx.kotoba.edn`: DataScript/kotoba-shaped entity maps
- `data/collection/catalog.datomic.edn`: Datomic schema and scalar `:db/add` txs
- `data/collection/runs/<timestamp>/<source>/`: raw response and run manifests
- `data/collection/media/sha256/...`: content-addressed images/videos

Small EDN stays directly in Git. Raw responses and media match `.gitattributes`
rules for git-annex. The active `murakumo-benjamin` encrypted rsync special
remote keeps another copy at `/Users/Shared/akashi-annex` on `benjamin`; the
collector copies annexed media and raw responses there on each successful
published run. Collection commits are pushed to `data/continuous-collection`.
The remote was initialized over Tailscale SSH with shared encryption:

```bash
git annex initremote murakumo-benjamin type=rsync \
  rsyncurl=benjamin:/Users/Shared/akashi-annex encryption=shared
git annex copy data/collection/media data/collection/runs \
  --to murakumo-benjamin
```

Shared encryption protects bytes at rest on the backup node; its decryption
material is intentionally stored in the repository's `git-annex` branch so a
restored clone can enable the remote.

Example queries:

```bash
clojure -M:query count-by-platform
clojure -M:query platform meta
clojure -M:query advertisers
clojure -M:query media
clojure -M:query --datomic data/collection/catalog.datomic.edn media
```

`config/datomic-queries.edn` also contains ready-to-transact/query Datalog forms
for a real Datomic database after applying the bundle's schema and tx-data.

## Install through Murakumo

After this change is merged to the akashi repository, run from the Murakumo
repository:

```bash
nbb scripts/run-task.cljs task plan \
  --tasks ../../cloud-itonami/akashi/deploy/murakumo-install-task.edn
nbb scripts/run-task.cljs task run \
  --tasks ../../cloud-itonami/akashi/deploy/murakumo-install-task.edn
```

As observed on 2026-08-01, Murakumo's current nbb/KIR task planner fails before
reading this task with a BigInt conversion error; after the first boundary is
corrected, its KIR string byte-offset path also fails existing offline tests.
The install task has therefore not been executed. Repair and green Murakumo's
task-plane tests before running the command above.

The task clones the repository, checks out `data/continuous-collection`, merges
the latest `origin/main` into that data branch, renders a token-free user
LaunchAgents, and starts both the collector and Tailnet-only public-data
service. A merge conflict stops the task for operator review. Inspect them with:

```bash
launchctl print gui/$(id -u)/com.murakumo.akashi-collector
launchctl print gui/$(id -u)/com.murakumo.akashi-public-data
tail -f "$HOME/.akashi/collector.log"
```

For a safe local check with no network, leave all sources disabled and run
`clojure -M:collect --config config/collection.edn` without `--publish`.
