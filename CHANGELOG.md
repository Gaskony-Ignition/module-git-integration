# Changelog

Gaskony builds of the OperaMetrix Git module. Versions up to 2.1.0 are
upstream's; everything below is this fork.

## [3.4.1] - 2026-09-18

### Added
- **Scheduled sync has a Replace mode.** Pull (the default, and what every existing sync keeps)
  fast-forwards and refuses while anyone has local changes. Replace makes the project match the
  branch exactly, as a release does: files the branch dropped disappear, uncommitted edits are
  overwritten and reported in the Event log, a branch moved backwards is followed — a rollback —
  and the gateway's own project properties (`ignition/global-props/data.bin`) are kept. Pointed at
  a branch that holds built releases, it installs a release with no runner.

### Removed
- **The runner tab no longer saves a gateway address.** The gateway never used it — it only
  filled in the check command — while deploys go wherever the workflow, or the promotion tool
  that starts it, says. Two places holding one address let a changed setting look like it
  steered a deploy when it did not. The check now has its own unsaved address field, starting
  at the one the page is open on. A settings resource saved by an earlier version loads with
  the old address ignored.

### Fixed
- **Scheduled sync set to a branch other than the one checked out** pulled that branch into the
  checked-out one, and — because it compared against a local branch that never existed — pulled,
  imported and rescanned the project on every interval, logging a sync each time with nothing
  changed. Sync now switches the project to the branch it is set to follow (only ever with no
  uncommitted changes, which it already refused) and fast-forwards it from there. This is what
  lets a gateway follow a branch of its own that a promotion tool moves.
- A remote branch that moved **backwards** (force-pushed, e.g. a rolled-back promotion) no longer
  counts as a sync on every interval. Sync reports it as not applied — it never resets a project.

## [3.3.0] - 2026-09-18

### Changed
- **One look across every tab.** The page now takes colour and weight from the gateway's own
  tokens and declares its type scale, spacing, radius and monospace stack once. Headings, hints,
  tables, button rows and status colours are one style each, where there were up to four. Hints
  are 13px everywhere (some were 11px), secondary grey text meets WCAG AA contrast, and every
  table scrolls in place rather than widening the page. History gained the same header as the
  other tabs.
- **The runner tab has one Save.** The gateway address sits in step 1 with *Accept requests*;
  the check at the bottom uses the saved address.
- The Help tab says how to promote to a gateway **without a runner**: give it a branch of its own
  and point Scheduled sync at it. Its credentials table no longer lists committing a workflow.

### Removed
- Everything 3.2.4 stopped showing: the runner install-script and example-workflow generators,
  the `POST /runner-workflow` route that committed a workflow, and nine unread fields of
  `GET /runner`. Only the check command is still generated.
- The runner's `labels` setting. The gateway never read it; a runner resource saved by an
  earlier version still loads, with that field ignored.

## [3.2.4] - 2026-09-17

### Changed
- **Actions runner steps 4 and 5 are now checklists.** Installing a runner and writing a workflow
  happen on GitHub, not in this module, so the tab says what each needs — a runner that can reach
  the gateway with a label of its own; a workflow whose `runs-on` names it, sending the token to
  the release or sync route — and leaves the commands to GitHub. The scope choice, the generated
  install scripts, the example workflow, the commit-the-workflow button and the labels field are
  gone. Runner and workflow detection stay. The gateway address now sits with the check command,
  the only generated text left.

## [3.2.3] - 2026-09-16

### Fixed
- **Changing a project's delivery left the Projects tab showing the old one** until the whole page
  was reloaded: switching tabs is not a remount, and the runner save invalidated only its own
  cache. It now invalidates the projects cache too, and giving a project a repository or a remote
  invalidates the runner's, since that is what gates Repo updates.

### Changed
- The Projects tab's Automation column reads `Runner · not chosen`, greyed, where it previously
  said `Runner · either`. The column describes the project's own configuration, and an enabled
  runner with no delivery chosen is something still to set up, not a setting.

## [3.2.2] - 2026-09-16

### Fixed
- **The delivery radio showed a default as though it were a choice.** A project nobody had chosen
  for rendered with *Release* selected, so it looked settled — and because clicking the option
  already shown fires no change event, the setting could not be reached at all. Both the radio and
  the project table now show what was actually chosen, with *Not chosen* and a note saying both
  routes are accepted until one is picked. The Projects tab was already reporting this correctly
  as `Runner · either`, which is how the discrepancy surfaced.

## [3.2.1] - 2026-09-16

### Added
- **An Edge section in the help tab**: what to do where this module cannot be installed — the runner
  is unchanged, but the release is copied into `data/projects/<project>/` under the name Edge
  itself carries, owned by the gateway user, and applied by a restart, since Edge has no project
  import.
- **An Automation column on the Projects tab**: whether a runner delivers to each project and as
  what (`Runner · Release`, `Runner · Repo updates`), plus any scheduled sync and its interval.
  A delivery chosen while the runner is switched off reads as `Runner off (…)`, and a project on
  an enabled runner that nobody has chosen a delivery for reads as `Runner · either`, because
  both routes answer for it.

## [3.2.0] - 2026-09-16

The Actions runner tab stops asking for work that is already done, and says which values the
gateway actually acts on.

### Added
- **A "Which should I use?" tab** on Automation: push (a runner) against pull (git-sync, which
  this module already provides as Scheduled sync and Repo updates); which delivery modes need git
  credentials on the gateway and which need none; and why a job with the wrong labels queues for
  ever without erroring.
- **Runner scope.** The install commands can now register a runner against the whole
  organisation, not only one repository — the right answer whenever more than one repository
  deploys to a gateway. Previously only the repository form was generated.
- **Detection instead of instructions.** Step 4 reports when a runner last called this gateway and
  collapses the install material; step 5 lists the workflows already in the project's repository
  and says whether any of them already calls a gateway, so a second workflow is not added beside
  one that works.
- Live preview: the generated commands follow the address and labels as they are typed.
- **The delivery choice is now enforced.** A release upload to a project set to *Repo updates* is
  refused with 409, and a pull request to one set to *Release* likewise, instead of quietly doing
  the other thing. A project nobody has chosen for still accepts either, so an upgrade changes
  nothing until a choice is made.
- **A table of every project and its delivery**, so a configured gateway looks configured. The
  project select chooses what to edit and is deliberately not remembered, which read as a setting
  that had been lost.

### Changed
- **Linux, macOS and Windows snippets are tabs**, opening on the platform the page is being read
  on, instead of three stacked blocks.
- **The gateway address and runner labels moved out of step 1** and are labelled as values used
  only to generate the commands. The gateway reads neither when a runner calls; the whole of what
  it acts on is *Accept requests from a runner* and the token.
- The example workflow is collapsed behind a disclosure and carries comments naming the three
  values that must agree, because a repository that already deploys should gain one step rather
  than a second workflow.

## [3.1.0] - 2026-09-15

The Actions runner can now install releases, not only pull a branch.

### Added
- **Release delivery.** A workflow uploads a project export zip to the new
  `POST /runner-release?project=<name>&version=<v>` route, authenticated by the runner token. The
  gateway replaces the whole project with it (files dropped from the release disappear), keeps the
  project's git repository and its project properties, and applies it without a restart. The
  project needs no repository on the gateway. Uploads are capped at 256 MB, and a zip with an entry
  outside the project folder or without `project.json` at its root is refused.
- A per-project delivery choice on the Actions runner tab: **Release** or **Repo updates**.
- A generated release workflow (`.github/workflows/ignition-release.yml`, on a `v*` tag) with Linux,
  macOS and Windows steps, and a release-mode reachability check that installs nothing.
- A macOS runner install snippet (the Apple-silicon build).

### Changed
- **Repo updates no longer needs a Scheduled sync record.** Without one it pulls the project's
  checked-out branch from its remote, with the credential that remote already uses.
- **No project is pre-selected.** Until one is chosen every snippet shows placeholders, so an
  example never carries a real project name or repository.
- The gateway address starts as the address the page is open on.

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
