# Changelog

Gaskony builds of the OperaMetrix Git module. Versions up to 2.1.0 are
upstream's; everything below is this fork.

## [3.0.1] - 2026-09-15

### Fixed
- History tab footer no longer shows "Showing 0 of 0 items" beside a full commit list.

## [3.0.0] - 2026-09-14

### Removed
- Event delivery (project-library/Gateway-Event dispatch of git operations) and outbound triggers (HTTP calls on a matching event, with GitHub presets) — both were a second channel for information the Designer and a GitHub remote already report.
- Routes `POST /automation`, `/automation-test`, `/trigger`, `/trigger-remove`; the `fetch`/`checkout`/`branch`/`revert` event types, which were advertised but never fired.

### Changed
- The Automation page states plainly that everything on it pulls changes in; pushing happens in the Designer or via Remote Sync.
- Event log's Delivery column is replaced by Details, which carries the remote name; Clear now also resets the event/failure counts.
- The Actions runner tab warns when the selected project has no Scheduled sync record, and disables *Commit the workflow* until one exists.
- The runner tab and generated workflow support Windows (PowerShell) runners alongside Linux; runner version is 2.337.0.
- Runner labels with spaces are normalised before reaching `config.sh`/`config.cmd`.

### Upgrade
- On first start, the gateway deletes the retired `git-automation`, `git-trigger` and `git-webhook` resources as one config-repository commit. Rotate any token ever typed into a trigger's headers — it remains readable in that repository's history.

### Breaking
- Any saved trigger or event-delivery handler stops working; there is no migration.

## [2.18.0] - 2026-09-10

### Fixed
- The generated workflow trigger now reads the project's own branch instead of hardcoding `main` (project-init creates `master`), which previously produced a workflow that never fired and never errored.

### Added
- A credential can be attached to a project's remote from the gateway page (`POST /project-credential`), not only from the Designer's Remotes popup.

## [2.17.0] - 2026-09-10

### Added
- *Commit the workflow to the repository* writes, commits and pushes `.github/workflows/ignition-sync.yml` itself; it refuses to overwrite a differing existing workflow and reports `unchanged` rather than making an empty commit.

## [2.16.0] - 2026-09-10

### Added
- Actions runner tab: generates the `config.sh` registration command, workflow YAML and reachability check for a GitHub Actions self-hosted runner, and opens `POST /runner-sync` (bearer-token authenticated, 64 KB body cap, 404 until a token is generated) for the runner to call. Runs even when the project's scheduled sync is off.

## [2.15.0] - 2026-09-10

### Fixed
- Form fields on the Versioning page had no visible label (the platform's `TextInput`/`SelectInput`/`TextArea` render `label` into an opacity-0 legend); `web-ui/src/pages/GitConfig/fields.tsx` paints the label above the control instead.

### Documentation
- Recaptured the Automation, Projects and Excluded files screenshots, and brought `CLAUDE.md` back in line with the current resource types, routes and automation feature.

## [2.14.0] - 2026-09-09

### Removed
- The inbound GitHub webhook added one release ago: it authenticated and pulled correctly but could never receive a delivery from GitHub on a gateway GitHub cannot reach. Scheduled sync is again the only inbound path; see `docs/AUTOMATION.md`.
- The `details` dictionary on events, which only the webhook ever populated.

### Kept from 2.13.0
- Non-ASCII in an event no longer stops delivery; script and trigger delivery no longer take each other down; opening *Add credential* no longer crashes the page; the image tree-walk export and per-project image folder.

## [2.13.0] - 2026-09-09

### Added
- Inbound GitHub webhooks: HMAC-authenticated, replay-rejected, fast-forwarding the matching project on `push`/`workflow_run`.
- Events carry a `details` dictionary for fields without a column of their own.

### Changed
- A project versions one named image folder; export now walks the full image-store tree instead of writing one empty file (the store is a tree, and the old code only read its top level). Import still merges.
- Images can be snapshotted from the gateway page, not only from the Designer's Commit panel.

### Fixed
- A non-ASCII character (accented letter, curly quote, em dash) in a commit message no longer stops event delivery.
- A failure in one delivery path (script vs. outbound trigger) no longer takes the other down.
- Opening *Add credential* no longer crashes the page (the platform's `Radio` is a radio group, not a single control).
- `${…}` placeholders now accept dots, so `${details.conclusion}` resolves.

### Known
- Field labels on these tabs are not painted (platform `TextInput`/`SelectInput` render `label` into an invisible legend); recorded in `docs/TROUBLESHOOTING.md`, along with a macOS JDK accessibility bug that can crash the Designer during a pull.

## [2.12.6] - 2026-09-09

### Changed
- Importing images now merges instead of clearing the gateway's whole image store first, and skips an image already present with identical bytes.

## [2.12.5] - 2026-09-09

### Fixed
- Cloning a project with no `images/` folder no longer wipes the gateway's image library; the import now no-ops in that case, matching tag/theme import.

## [2.12.4] - 2026-09-09

### Fixed
- Cloning a project new to this gateway no longer fails with "exists but is not empty"; it is now adopted from disk by a scan instead of an import call that refuses a non-empty directory.

## [2.12.3] - 2026-09-09

### Fixed
- A resource deleted on the remote no longer survives a clone; checkout cleanup now sets `setCleanDirectories(true)`.

## [2.12.2] - 2026-09-08

### Fixed
- Dropdown selections now save correctly (`SelectInput.onChange` returns MUI's event, not the chosen value); a malformed request now produces a useful error instead of an opaque 500.

## [2.12.1] - 2026-09-08

### Fixed
- Dropdowns render instead of blanking the page (`SelectInput` takes `values`, not `options`), including the Credentials and Projects tabs' secret/credential pickers.

## [2.12.0] - 2026-09-08

### Added
- Automation tab: git events (commit/push/pull/checkout/branch/revert/auto-commit) delivered to a project-library function or Gateway Event handler; outbound triggers with GitHub presets; scheduled sync with a dirty-tree refusal; an event log.

### Fixed
- `checkoutRemote` now honours the remote it was given instead of hardcoding `origin`.

## [2.11.1] - 2026-09-08

### Fixed
- An init that failed part-way no longer strands a project on an unborn branch; a `.git` with no HEAD is now completed or cleaned up, and Pull on such a repository finishes the clone instead of attempting a merge.

## [2.11.0] - 2026-09-08

### Added
- Credentials tab (SSH keys, HTTPS credentials) and Projects tab (every project's git state, initialise/clone/remote without a Designer), both usable before any project versioning is set up.

### Fixed
- The page no longer hides the Credentials/Projects tabs behind "config versioning is not initialized".

## [2.10.0] - 2026-09-08

### Changed
- The Changes list and Project Browser badges refresh on save instead of waiting for the next poll tick.

## [2.9.0] - 2026-09-08

### Fixed
- Change badges no longer overlap the resource name.

## [2.8.0] - 2026-09-07

### Fixed
- A nested git repository under `config/` is no longer reported as a config change.

## [2.7.0] - 2026-09-07

### Changed
- Excluded files tree is rooted at `config/`, sized to its content; module renamed to "Git Integration".

### Fixed
- Commits stage exactly the paths the change list reports, not the whole data directory.

## [2.5.0] - 2026-09-07

### Fixed
- Badges keep rendering after the first commit of a Designer session (drawn by a border instead of the platform's badge collection).

## [2.4.0] - 2026-09-07

### Added
- Deleted resources show as a red badge rolled up onto the ancestor folder.

## [2.3.0] - 2026-09-07

### Added
- Change badges in the Designer's Project Browser; Excluded files tab on the gateway Versioning page.

### Fixed
- `initRepo` no longer races the tag value store (`*-wal`/`*-shm` now ignored).
