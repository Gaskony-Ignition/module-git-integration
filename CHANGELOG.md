# Changelog

Gaskony builds of the OperaMetrix Git module. Versions up to 2.1.0 are
upstream's; everything below is this fork.

## [4.5.0] - 2026-11-12

### Fixed
- Configure versioning showed a remote's saved Secret Provider reference blank when the
  definition loaded after it, so changing only the branch asked for the secret again. The saved
  reference is shown, and a save with it left blank keeps it; a new host still asks for it.

### Changed
- Deployment-mode overrides now use the gateway's own Resource Definitions panel. The Policy tab,
  and the new **Remote** and **Runner** tabs, are the platform's settings page: Save Changes,
  "Select Definition to Configure", and the ⋮ menu's **Create Override**, **Move Definition** and
  **Remove Override**, as on Gateway Settings or a historian. Each project with a delivery gets the
  platform's row ⋮ on the Projects tab, and the project and credential drawers' menus gain Move
  Definition. The module's own "Save to" and definition picker are gone from those three records.
- The gateway repository's remote moved from Configure versioning to the **Remote** tab, and Runner
  access from the Credentials tab to the **Runner** tab. Configure versioning keeps delivery,
  layout and Delete versioning. Saves still go through the module, so a blank secret keeps the
  definition's, a new host asks again, and hosts are checked against the policy.
- The policy, the gateway remote and the runner settings are stored as platform singletons
  (`<collection>/com.operametrix.ignition.git/git-policy/` instead of `.../git-policy/policy/`).
  At start-up each one is moved, in core and every mode, then the config is rescanned; the
  versioned-policy `.gitignore` lines already cover the new place. If the move fails the policy
  reads as locked until it succeeds.
- The gateway's audit profile gets a row for an override created, moved or removed from the
  platform's menu too.
- The gateway repository's `.gitignore` keeps the module's own lines between
  `# >>> Git Integration` and `# <<< Git Integration` markers, and the never-versioned key and
  certificate lines in a second marked block that is always moved back to the end of the file.
  Module writes (initialise, layout switch, start-up updates, Git ignore tab ticks) change only
  those blocks, so lines you or another tool add outside them stay where they are. An existing
  file is converted once at start-up, in one commit of `.gitignore` alone.

### Added
- Join remote history lists, in its confirmation, the files on the remote branch outside
  `config/` (and `projects/` in the Combined layout) that this gateway does not have, which the
  join commit deletes from the branch.

## [4.4.5] - 2026-11-12

### Added
- Policy tab: a "?" beside each setting, on the page and in Edit policy, opens a short
  explanation of what it allows; the Audit log row's description moved behind one too.

### Changed
- A repository made before 4.2.0 that still sits on a lone `master` while its remote's branch is
  `main` is renamed to `main` by itself when the Versioning page first shows it (gateway
  repository and each project), with a line on the Logs tab. The page no longer reads
  "Remote sync (master → main)". When the policy turns branch changes off, the Rename button is
  offered instead; a delivery that follows `master` by name keeps it.

## [4.4.4] - 2026-11-12

### Added
- "Keep this gateway's project properties", per project, in the Projects drawer for Sync — replace
  and Runner — release. On (the default, and what every earlier version did) a replace puts the
  gateway's own `ignition/global-props/data.bin` back; off takes the branch's or the zip's, keeping
  the gateway's only when the incoming project has none. The run's log entry says which, and
  keeping properties that name no identity provider when the incoming ones name one logs a
  warning once per incoming file. `POST /delivery` takes `keepProjectProperties`.

## [4.4.3] - 2026-11-12

### Fixed
- Combined: a Designer's checkout of a branch or commit stashed the whole gateway repository,
  other projects' and config's uncommitted work with it. It is now refused, naming the files,
  while anything in the repository is uncommitted (files Ignition only reformatted do not count),
  as the page's branch switch is.
- A Designer pull, checkout, revert, merge abort or merge completion no longer throws away the
  Designer's unsaved edits: it is refused, listing them, until they are saved. Discarding one
  resource's changes drops only that resource's Designer edits. An edit still in an open view
  tab is not seen until the tab hands it to the project (switching tabs or saving does), so save
  before these actions.
- Discarding one resource from the commit panel deleted its whole folder when Ignition had
  written a new file into it (a view's `thumbnail.png`), removing the resource. Its tracked files
  now go back to the last commit and only its new files are deleted.
- Scheduled sync checks for uncommitted changes before fetching again, so a project with local
  work makes no network call every interval.
- Combined: a config auto-commit (or any config commit) while a Designer's pull was in conflict
  completed that merge as a gateway commit, dropping the remote's changes. No config commit is made
  while a merge, revert or cherry-pick is open; the auto-commit retries every 30 seconds, and page
  pulls, branch switches, restores, the layout switch and Join remote history are refused. A
  conflicted file is never staged.
- A merge left open (the Designer closed mid-conflict) can be finished again: a Designer pull or
  checkout offers the conflict dialog, and the Versioning header shows Merge in progress with
  Abort merge.
- A pull that stops on a conflict (Designer Abort, the page's Pull, a project sync) puts back only
  the files the merge wrote instead of hard-resetting the repository, which in Combined took every
  other project's and config's uncommitted work.
- A pull after an import no longer fails with a checkout conflict over files Ignition only
  reformatted, in any project (Combined) as well as the one pulling.
- A revert that stopped before writing anything no longer undoes files.
- A project's sync, its Designer's commit, pull, checkout and abort, and the page's branch switch
  now take the same lock, so a sync cannot merge into a project mid-commit. Fetches run before the
  lock.
- Sync now showed a green toast when the sync failed or did not pull; it now shows an error. It
  also refuses a project whose Sync delivery is off.
- Sync — replace in Combined reported "discarded 0 uncommitted changes" while it discarded
  projects' changes; it counts the whole repository.
- Update from remote on an initialised repository hard-reset over uncommitted changes; it is now
  refused, naming them.
- A gateway pull that switched branch before its merge failed said "nothing was changed"; it now
  says which branch the gateway is on.
- Combined: Push all branches from the Designer skipped the remote layout check; every branch is
  checked.
- The auto-commit hold keeps the later of two deadlines, and the SSH host-key fingerprint in a
  remote test is per call, not shared between two tests at once.
- Switching to Separate warns when a project's Sync delivery is on but its new repository has no
  remote.
- The History tab's Author column stays on one line.

### Security
- `POST /delivery` took the credential owner from the request body, so a WRITE user could make a
  delivery authenticate with another user's stored credential. Only the caller, or the owner
  already set, is accepted (403 otherwise).
- Saving the config remote with a different host and no new secret kept the old host's secret;
  it now asks for the secret again.

### Changed
- docs/LAYOUT.md and docs/MODES.md say plainly that in Combined any Designer user's pull,
  checkout, push and force push act on the whole gateway with the gateway's credential, and
  recommend turning off branches and force push in a Prod policy override. LAYOUT.md no longer
  says per-project remotes come back after switching to Separate without restoring history, and
  notes that stashes and unpushed commits stay in the archive.

## [4.4.2] - 2026-11-12

### Fixed
- A revert that failed (a conflict with later changes) discarded every uncommitted change in the
  project, not just its own; in the Combined layout, every uncommitted change in the project. It
  now puts back only the files the commit changed, and a revert whose files have uncommitted
  edits is refused before anything is touched.
- A revert straight after Pull and Import no longer fails with "conflicts detected": files
  Ignition only reformatted on import (same content as the commit) no longer count as edits.
- Switching to a branch whose saved uncommitted changes no longer apply keeps them in the stash
  (and says so on the Logs tab) instead of dropping them.

## [4.4.1] - 2026-11-12

### Added
- Designer actions now write the same audit rows as the gateway page: a pull, a checkout of a
  commit, a revert, and a remote linked, added, changed or removed (push, branch switch and
  credential changes already did).
- A row from a Designer names the Designer's host as the actor host and Designer as the
  originating context, instead of `gateway`.

## [4.4.0] - 2026-11-11

### Security
- A remote URL whose user name is a token (`https://ghp_…@github.com/…`) is refused, and remote
  URLs are logged and audited without their user part.
- Internal user sources' `users.json` (password hashes and their history) is never versioned, in
  every collection. Start-up untracks it in an existing repository in one commit and warns on the
  Logs tab: older commits still hold the hashes, so rotate those passwords if the repository was
  shared.
- Switching Combined → Separate is refused, naming the folder, when a project folder is a link or
  mount leading outside the data directory or sits inside another git work tree, so no `.git` is
  planted in someone else's checkout.

### Added
- **Add from repository** on the Projects tab: brings in a project the gateway does not have —
  repository URL, your credential, branch (the remote's default), project name (from the
  project's title) and an optional delivery. Cloned under `var/` first; refused without a root
  `project.json`, for a name already in use or a URL the policy refuses, and nothing is left
  behind on failure. Separate layout only.
- **Fetch** and **Pull** for the gateway repository beside Remote sync, and **Join remote
  history** for a remote branch another gateway started (no common history): this gateway's
  config is committed on top of the remote tip, and nothing on the gateway changes.
- Delete versioning keeps the saved remote unless *Also remove the saved remote* is ticked.
- A leftover `projects/<name>/.git` no longer blocks switching back to Separate for good: tick
  *Archive leftover .git folders*.
- **Audit log.** One row in an audit profile per essential action: a push (page or Designer), a
  delivery installed (sync or runner), a Pull that brought commits in, Join remote history, a
  project added from its repository, a branch switch, a layout switch, a restore or update from
  the remote, a policy change, a credential added or removed, a mode override created or removed.
  Each carries the user, project, branch and commit. Polls, fetches, auto-commits, per-file detail
  and refusals stay on the Logs tab only. **Policy › Audit log** picks the profile: by default the
  gateway's own (Security › General Settings), else Off. Writes run in the background and never
  hold up the git action; a failing profile is logged once.
- **Only deliver commits merged on GitHub**, a policy option, off by default and overridable per
  mode: deliveries (Sync — pull/replace for projects and the gateway repository, Runner — repo
  updates, and Add from repository) install a branch tip only when GitHub's web-flow key signed it
  and the remote is GitHub (`github.com` or a listed GitHub Enterprise host); the commit checked is
  the commit merged, with no second fetch. Others are refused,
  with one warning per tip on the Logs tab; the runner route answers 409. GitHub's keys are bundled
  in the module (docs/MODES.md); nothing is fetched at run time.
- Combined layout: the config ignore rules apply under `config/` only, so `!/projects/` alone
  re-includes projects and rules written under `projects/` work; `global-props/data.bin` is an
  ordinary Git ignore tick (excluded by default). Existing Combined repositories are updated once
  at start-up.
- Regression suite: audit rows for each essential action and none for a busy sync loop, and
  GitHub-merged-only refusing an unsigned tip and installing a signed one (a throwaway key stands
  in for GitHub's).

### Changed
- Shorter policy refusals ("Branch changes are off in Prod (Git policy).") on the page and in the
  Designer, and the loosened-policy warning ("Policy now allows more in Prod: push to remotes.").
- Versioning page: sentence case throughout (Configure versioning, Remote sync, Git ignore) apart
  from the platform's own labels; a row count beside each tab's title and a fade at the bottom of a
  table while more rows sit below; the Policy tab names where its settings come from once and
  colours a value red only when it is the less safe choice; Remove buttons in the platform's
  destructive style.

## [4.3.1] - 2026-11-11

### Security
- Testing a remote on the Versioning page followed an HTTP redirect, credential and all, to
  whichever host the server named. It no longer follows redirects, like every other remote
  operation since 4.3.0.
- A project's delivery could be saved under a name that is not a project name (`..`, `a/b`);
  it is now refused.

### Fixed
- Versioning page: the Configure Versioning drawer lost all its spacing (hints ran into the next
  label, Test connection touched Remove remote); the Branches graph cut off each commit's hash,
  author and age on a laptop screen instead of shortening the message; the layout drawer's
  restore-history tick stayed active when the policy refuses a layout switch; the checked-out
  branch's row now says Checked out instead of a greyed Switch button.
- Shorter page text: the header's "Push off in Prod", and the History, Credentials, Policy,
  credential and Configure Versioning hints.

### Added
- A regression suite (`test/regression/run.sh`, docs/TESTING.md) that runs the module on a
  throwaway gateway against TLS git servers in both layouts and three deployment modes.

## [4.3.0] - 2026-11-11

### Security
- **Designer calls act as the signed-in Designer user on the open project.** The user and project
  names a call carries are no longer trusted: credentials, ownership and commit authors come from
  the Designer session, and a Designer can only act on its own project. Attaching a credential to
  a remote (Designer, Projects tab, project setup) requires it to be your own.
- **Project names and file paths are checked.** A project name must be an Ignition project name
  and the project must exist; file paths in diffs, discards and conflict views must stay inside
  the project and repository, cannot name `.git`, and a symbolic link cannot lead outside. A
  working-tree diff reads at most 5 MB of a regular file, and the Versioning page only shows files
  the repository versions, never a key, certificate or local-config file even from history. In the Combined layout a path outside the project is accepted only for
  a file in merge conflict.
- **TLS certificates are verified.** Earlier versions turned certificate checks off in every
  repository they created. They are back on, and start-up turns them on in existing repositories
  (logged). A git server with a private CA needs that CA in the gateway's Java truststore
  (docs/TROUBLESHOOTING.md).
- **HTTPS credentials go only to their own host.** The host field is required and always binds
  the credential. A credential from an earlier version whose field was a label is bound at
  start-up to the one host its remotes use; otherwise it is refused until its host is set, and
  the gateway log says which. Moving a project remote to another host detaches its credential.
  The remote test only resolves a Secret Provider reference that a saved credential for that host
  already uses, and which provider secret a reference names is shown only with WRITE permission
  (`POST /secret-ref`). The GitHub token check only runs for credentials bound to github.com.
- **Remote URLs must be HTTPS or SSH.** Local folders, `file://`, `git://`, `http://` and targets on
  this machine or its link-local network (`localhost`, `127.x`, `169.254.x`) need the new policy
  setting **Insecure remotes** (off by default). A URL with a password in it is refused
  everywhere, and start-up removes such passwords from existing remotes (logged as a warning:
  enter them again as credentials).
- Remote URLs are read the way JGit reads them (`file:/x` is a local folder, `host:path` is SSH),
  and a host that is not a valid host name or IP address, or a URL an HTTP client would read
  differently, is refused. The allowed-hosts list and credential binding use the same reading.
- HTTP redirects are not followed, so a credential only reaches the remote's own host.
- A Git policy that exists but cannot be read is treated as locked, not as the permissive default.
  A delivery, restore, override or mode switch that loosens the effective policy is logged as a
  warning on the Logs tab and in the gateway log.
- Only a Designer session can make the module's Designer calls.
- Checkouts write symbolic links as plain files (`core.symlinks=false`), set on new and existing
  repositories.
- Legacy plaintext SSH keys are re-saved encrypted at start-up and the plaintext is no longer
  read.
- The data directory's `.gitignore` always ends with a fixed block that keeps keystores,
  certificates, `config/local`, `config/resources/local`, `ignition.conf` and key files out of
  the repository; no edit can re-include them. Tracked files it covers are untracked at start-up
  (earlier commits still hold them).
- Runner release: unpacks to at most 1 GB and 50,000 entries, and the `version` label is limited
  to 64 letters, digits and `. _ + -`. A bad runner token is logged once a minute per address.
- Listing Secret Provider secrets needs WRITE permission (`POST /secret-providers`, was `GET`). Errors shown on the page and in the Designer no
  longer name the gateway's install folders; the gateway log keeps the detail.
- History pages return at most 500 commits; fetch, push, pull and ls-remote time out after 60 s.
  In the Combined layout the Designer's pull fetches before taking the gateway repository's lock;
  in the Separate layout each project repository has its own lock.

### Changed
- Dependencies: JGit 7.8.1, Apache MINA sshd 2.20.1, slf4j 2.0.20 (and jcl-over-slf4j to match),
  commons-codec 1.23.0; web-ui React pins 18.3.1.
- Docs: a production checklist (docs/MODES.md) and why branch protection on the remote is the
  control for delivered branches (docs/AUTOMATION.md).

## [4.2.0] - 2026-11-10

### Changed
- **Overrides work like the platform's own resource pages.** Every overridable drawer (policy,
  config remote, credentials, a project's delivery, runner access) has the platform's kebab in its
  header: **Create Override** into any deployment mode created on the gateway, not only the active
  one, and **Remove Override**. Once a record has more than one definition, **Select Definition to
  Configure** picks which one the drawer edits and Save writes; the definition in use is marked
  "(active)". Lists say which modes override each record ("Overridden in Prod"), and a credential
  that only a mode has is listed too. Gateway: `POST /mode-override`, `GET /definition`,
  `/mode-reset` takes any mode, and `?saveTo=` any mode. The REST `resources/copy` path still works.
- **Projects: a Branches button on each versioned project's row** opens its branch graph
  directly. The Branches tab inside the Edit drawer is gone, so there is one way in.
- New repositories (the gateway repository, a project's local repository, the Combined →
  Separate split) start on the configured remote branch, else `main`, never JGit's `master`.
- Repository layout card: the restore-history option is the platform checkbox on its own row.

### Added
- **Rename local master to main**: a repository from before 4.2.0 whose one local branch is
  `master`, behind a remote whose branch is `main`, offers the rename (Versioning header for the
  gateway repository, the Projects Branch column for a project). Same commits, upstream kept.
- Branches with no remote say so, with a button to where the remote is set.
- **Existing repositories laid out another way are refused, not merged.** Before the gateway
  repository pushes or pulls (Remote Sync, Update from remote, the config follow, a Combined
  Designer push or pull), a branch with no `config/` at the top or with history unrelated to this
  gateway's is refused with the restructure steps; see docs/LAYOUT.md, "Using an existing
  repository". Nothing is pushed over it.

### Fixed
- **Pulling a branch whose remote branch was deleted** (merged on GitHub, then deleted) showed
  JGit's `RefNotAdvertisedException`. The Designer's pull now fetches with prune first and says
  "feature-1's branch on origin was deleted — usually because it was merged", offers **Switch to
  main and pull** (the remote's default branch, else the configured one, else main/master), and
  afterwards offers to delete the local branch when it is merged, or says how many commits it
  would lose. The sync timer, the runner and the gateway repository's follow log a plain warning
  and stay on their branch.
- Other raw JGit failures on pull, push and fetch read as plain sentences (credential refused,
  repository not found, host unreachable, …); the original is kept under Details in the Designer
  and in the browser console on the page.

## [4.1.0] - 2026-11-10

### Added
- **Git policy, per deployment mode.** A new `git-policy` resource (Versioning › Policy) says
  whether this gateway may push, force push, commit from the Designer, create/switch/delete
  branches and switch the layout, which deliveries it allows (Sync — pull, Sync — replace, Runner;
  Off always), and which remote hosts it may contact. The default is earlier versions' behaviour.
  Every path asks it: Designer RPCs, the page's routes (403), the sync timer, the config follow and
  the runner. Refused controls stay visible, disabled, with the reason. See docs/MODES.md.
- **Per-mode records.** The config remote, credentials, sync deliveries, runner access and the
  policy can be overridden per mode — from the page (**Save to: core | <mode>**, **Reset to
  core**, a "from core / from Prod" tag) or from Platform › Modes and `resources/copy`, which
  answered "COPY operation not allowed" before.
- The page header shows the deployment mode, and commits made by the gateway or the Designer
  while a mode is active end with a `Gateway-Mode: <mode>` trailer.

### Changed
- The Git policy is versioned with the config; every other record of this module still is not.
  New repositories ignore `**/com.operametrix.ignition.git/*` with
  `!**/com.operametrix.ignition.git/git-policy/`; an existing `.gitignore` has its one
  `**/com.operametrix.ignition.git/` line rewritten into those two once (marker
  `.git-module-policy-versioned`) and nothing else touched.

### Fixed
- The config repository pushed and fetched through `origin` in `data/.git/config`, set only when
  the remote was saved, while authenticating with the remote record the active mode resolved: after
  a mode change one mode's credential was sent to the other mode's URL. Origin is now set from the
  effective record (URL, branch, no push URL) before every push, fetch and pull.
- A credential is no longer sent to a host other than its own: the gateway remote's credential in
  Combined to a Designer-added remote elsewhere, an HTTPS credential to a host its host field does
  not name, or a saved credential tested against a URL on another host.
- The credential check no longer contacts a host the Git policy does not allow, nor sends an HTTPS
  credential to a host its host field does not name; saving the config remote checks its credential
  at once.
- Commit and revert emails come from the user's contact info again: on 8.3 the user-source lookup
  read an empty legacy table, so every Designer commit had no email.
- The Combined pull-guard refusal shows its whole file list in the Designer (the error dialog cut it
  off). The Credentials table scrolls in place so the tab fits 1366×640.
- A revert from the Designer is authored by the Designer user (with their email) instead of "ignition". The revert commit is rewritten in place with the same tree, parent and message, so nothing staged or dirty is touched in either layout.

## [4.0.0] - 2026-11-10

**Type:** MAJOR

### Added
- **Branches on the gateway page.** A commit graph (lanes, ref chips, HEAD, gone/merged/ahead/behind
  badges) beside a branch list, for each project (Projects tab, project drawer) and for the gateway
  config (Versioning page). In Combined it shows the gateway repository and dims commits that leave
  the project. The Projects list has a Branch column with ↑/↓. **Switch to this branch** changes the
  branch from the page: refused with the file list when there is uncommitted work, or when a sync or
  delivery follows another branch; it then loads the result and logs a `checkout` event with the
  signed-in user. An open Designer reloads the project within ten seconds, or, with unsaved edits,
  says to save or discard them and update.
- **Branch clean-up in the Designer.** The local branch list marks branches `[gone]` (deleted on
  the remote), `[merged]` and `↑N` (commits on no remote), and the sync branch shows as protected.
  **Clean up…** fetches, then offers every gone or merged branch, ticked, and deletes the ones you
  keep ticked.
- **Repository layout: Separate or Combined.** Separate is every earlier version's layout and stays
  the default. Combined keeps config and every project in one repository at the data directory.
  Designer commits, diffs, history, reverts and badges stay limited to the project's folder;
  branches, pull, push and the remote become gateway-wide, and a checkout reloads config and every
  changed project. Switch either way from **Configure Versioning › Repository layout**; refused
  while anything is uncommitted. Going Combined archives each project's `.git` under
  `var/git-archive`; going back can restore it.
- **Gateway delivery: Sync — pull and Runner — repo updates** (Combined only), beside Sync — replace.
  In Combined a project's own delivery is Off or Runner — release; a release and a project delete
  are committed for that project's folder.
- **Remote Sync pushes the checked-out branch** to its upstream — the first time, the home branch to
  the configured branch and any other to its own name — never another branch onto the configured
  one. The button and the result name both branches.
- **RPC:** `getRepositoryLayout`. **REST:** `POST /layout`; `/status` and `/projects` carry `layout`.

### Changed
- **Delete is safe by default.** An unmerged or unpushed branch is refused with the commit count;
  **Delete anyway** overrides that. The checked-out branch and the branch a scheduled sync follows
  are never deleted (in Combined, the branch the gateway delivery follows). Before, anything but
  the current branch was force-deleted after one prompt.
- **Fetch prunes.** Branches deleted on the remote leave the remote list on the next fetch (Designer
  fetch, scheduled sync and the config repo's fetches).
- **RPC:** `deleteBranch` takes a `force` flag and returns a refusal as text; new
  `getLocalBranchInfo` and `cleanupBranches`. `getLocalBranches` is unchanged.

### Fixed
- **A scheduled or runner pull that hits a merge conflict is undone.** It used to leave conflict
  markers in the project's files and the repository mid-merge, so every later sync refused.
  `/runner-sync` now answers 500 with `ok: false` for a failed pull, as it does in Combined, so the
  workflow step fails.

## [3.11.3] - 2026-11-04

**Type:** PATCH

### Fixed
- **Deleting a project now removes the module's records for it**: its repo, user and remote
  credential records, its scheduled sync and its runner delivery. They used to stay behind, so the
  sync failed on every tick, the Credentials tab reported the credential's link to the project as
  broken, and a project later created under the same name inherited the delivery and credential.
  Projects deleted while the module was not running are cleaned up at its next startup.
- **Renaming a project is handled as a delete.** Ignition's rename builds the project in a new
  folder and deletes the old one, `.git` included, so the renamed project shows as not versioned
  and has to be linked again from the Projects tab.
- **Linking a project from the Projects tab is one request.** The drawer used to clone, then save
  the delivery in a second call, so leaving the page while it said "Saving…" left the project
  linked with Delivery Off. The gateway now sets the delivery as part of the init.

## [3.11.2] - 2026-11-04

**Type:** PATCH

### Fixed
- **Putting a project under version control from the Projects tab now clones the Branch you set.**
  It always cloned the remote's default branch and only recorded the Branch afterwards, for the
  sync. A branch that is not on the remote now fails with the list of branches that are.
- **A branch without `project.json` at its root is refused before anything on disk changes.** The
  clone cleans and hard-resets the project folder, so such a branch wiped the gateway's project,
  dropped it from the Projects list and left no way to edit it. The project and its records are
  now left as they were.
- **Saving a pulling delivery for a folder with no root `project.json` says so,** instead of
  reporting that the project has no remote.

## [3.11.1] - 2026-11-03

**Type:** PATCH

### Security
- **SSH host keys are now checked (trust on first use).** Every SSH connection used to accept any
  host key, so a server could be impersonated without a trace. The first connection to a host and
  port now records its key in `<data folder>/var/git-module/known_hosts` (outside the config
  repository, so it is never committed); a later connection that presents a different key is
  refused, with an error naming the host and both SHA256 fingerprints. The Configure drawer's
  connection test shows the server's fingerprint so it can be checked against the server. Existing
  SSH remotes record their key on their next connection, so that first connection is still
  unverified. To accept a rebuilt server's new key, delete its line from the `known_hosts` file.

## [3.11.0] - 2026-11-03

**Type:** MINOR

### Security
- **Ed25519 signature-malleability advisory (GHSA-p53j-g8pw-4w5f) no longer applies.** The module
  bundled `net.i2p.crypto:eddsa` 0.3.0 through `org.eclipse.jgit.ssh.apache` 6.10.1, and eddsa has
  no patched release. JGit 7.8's SSH artefact does not depend on it, so the jar is gone from the
  `.modl`.

### Fixed
- **The "Remote" chip on the History tab failed WCAG AA contrast.** Its text was dark blue on a
  solid blue fill (1.49:1); it is now white (4.62:1). The same fix applies to the rename and copy
  chips in an expanded commit.

### Changed
- **JGit 6.10.1 to 7.8.0** (`org.eclipse.jgit` and `org.eclipse.jgit.ssh.apache`), with Apache MINA
  sshd 2.14.0 to 2.19.0. No source changes were needed. With eddsa gone, Ed25519 SSH keys are
  handled by the JDK; no BouncyCastle jar is bundled. Tested on an 8.3.10 gateway against Gitea
  28.3.0: init, commit, push and pull over HTTP with a password, and push and pull over SSH with
  Ed25519, RSA and ECDSA keys, all succeed; an unregistered key and a wrong password are refused.
  SSH host keys are still accepted without verification, as before.

## [3.10.2] - 2026-11-03

**Type:** PATCH

### Security
- **Bundled web dependencies updated for Dependabot alerts.** The Versioning page bundles `qs`
  (6.17.1; GHSA-4mjr-xmp4-gh2g, GHSA-x5fp-wj9c-mxmx, GHSA-6pq8-jhm2-2vcw) and `react-router-dom`
  (6.30.6; GHSA-jjmj-jmhj-qwj2), both now past their medium advisories. The webpack build tooling
  moved to current majors (webpack 5.112.2, webpack-dev-server 6.1.0, webpack-cli 7.3.1), and a full
  `yarn upgrade` cleared the other build-time alerts. The web build now downloads Node 24.22.2
  (was 18.0.0), which the updated tooling needs. No behaviour change.
- **Still open:** two medium react-router advisories (GHSA-wrjc-x8rr-h8h6, GHSA-337j-9hxr-rhxg) are
  fixed only in react-router 7. `@inductiveautomation/ignition-web-ui` pins `react-router-dom` at
  `^6.30.4`, so the page stays on 6.30.6 until Inductive Automation moves. Also open: minimatch
  (GHSA-g3hv-75wg-m249) and deepmerge (GHSA-gcqx-w48m-w2h4), build-time only.

## [3.10.1] - 2026-10-13

**Type:** PATCH

### Fixed
- **The Git Ignore tab showed everything under `config/` as excluded.** Its rule matcher tested
  the root rule `/*` against each file's own name as well as its path, so every file at every
  depth matched it and `!/config/` never re-included them. Only the tab was wrong: the repository
  went on versioning `config/` as before. A rule that starts with `/` now applies at the root only.

## [3.10.0] - 2026-10-13 — withdrawn, use 3.10.1

**Type:** MINOR

### Added
- **The gateway config repository can follow a branch.** In Configure Versioning, Delivery is Off
  (the default) or Sync — replace, with a branch and a check interval (30 seconds or more, 300
  by default). On each interval the gateway fetches the branch and, if it moved or the config no
  longer matches it, makes the config match it exactly: local commits and uncommitted changes are
  discarded, files the branch dropped are removed, a branch moved backwards is followed, and a
  settings rescan applies the result with no restart. Gitignored runtime files are never touched.
  Each run is a Logs entry with the commit applied and how many local commits and changes were
  discarded; Sync now runs it on demand. The auto-commit is held while it runs, so nothing is
  committed on top of the branch. Needs a config remote; the setting is kept in the
  repository's `.git/config`, outside the versioned files.
- A new config repository ignores this module's own records (credentials, remotes, deliveries),
  which belong to the gateway they were set on. An existing repository keeps its own `.gitignore`.

## [3.9.12] - 2026-10-12 — withdrawn, use 3.10.1

**Type:** PATCH

### Fixed
- **The config repository versions only `config/` by default.** 3.8.0 staged everything
  `.gitignore` did not exclude and added a fixed list of data-root files, which missed what other
  modules keep there — upgrading a gateway with the Python3 and Playwright modules committed
  29,000 files (their CPython and Chromium installs). `.gitignore` now excludes the data root
  apart from `config/`; tick a root path on the Git Ignore tab to version it. On upgrade the rules
  are added above the Versioning page's own section, so paths already ticked on stay versioned,
  and whatever they exclude is untracked in one commit. History keeps those files' objects; a
  repository that needs the space back has to be rewritten.

## [3.9.10] - 2026-10-12

**Type:** PATCH

### Changed
- The Projects drawer's Delivery list shows every option. Without a remote, Runner —
  repo updates, Sync — pull and Sync — replace are listed but disabled rather than
  hidden, so it is clear they exist.

## [3.9.9] - 2026-09-25

**Type:** PATCH — accessibility (WCAG 2.1 AA)

### Changed
- The Versioning page and its drawers now meet WCAG 2.1 AA: every field's visible
  label is programmatically associated with its control (Search, Jump to,
  Credentials and Project forms, the raw `.gitignore` editor); a visible focus
  ring reaches every control, including the Configure Versioning and per-item
  settings drawers; the Remote/Local and per-file-change chips, and the outlined
  and link-styled buttons, now meet 4.5:1 text contrast; a locked row in the Git
  Ignore tree dims its icon and checkbox rather than its already-legible text.
  A handful of platform-drawn controls with no naming prop of their own (the
  gateway's header logo, the History table's page-size selector and row-expand
  button, the raw `.gitignore` editor's resize handle) are recorded as
  exceptions in `a11y.json`.
- The Designer's git popups gained keyboard mnemonics on their buttons, and the
  status bar's branch button gained a tooltip.

## [3.9.8] - 2026-09-21

### Added
- **Navigation for the Git Ignore tree.** A search box finds a file or folder anywhere under the
  data directory (a walk, capped, that says so when it stops early); **Jump to** opens one
  top-level folder on its own; **Show excluded** hides everything that is not versioned; and
  **Expand** / **Collapse** open three levels of versioned folders or shut them all. Expand skips
  excluded folders, so it never fetches `logs/` or `db/`.

- **A help button on Git Ignore** explains what is left out of the repository and why — runtime
  state, anything particular to one machine, and the project folders. It describes kinds of file
  rather than naming them, so an Ignition upgrade cannot make it wrong.

### Changed
- **Credentials has its own Last checked column.** It was squeezed under the expiry date, where it
  read as part of it. It shows the date too once the check is older than today.
- **The Git Ignore page leads with one line.** The legend moved under the tree and shows the marks
  themselves — a tick, an empty box, a dash — instead of naming them, and the explanation moved
  into the help button. The tree, not the prose, now fills the page.
- **Long lists scroll in their own box, sized to the window.** Gateway History and Projects ran the
  gateway's content pane off the bottom of a laptop screen; both now keep their page header and
  actions in place, and a bigger screen gets more rows rather than more empty space. Table headers
  stay put while the rows scroll.
- **Table action buttons are spaced.** Check, Edit and Remove were touching.

## [3.8.0] - 2026-09-21

### Changed
- **`.gitignore` is the only thing deciding what the config repository versions.** The module also
  staged a hard-coded scope of `config/`, so a data-root file nothing ignored — `gateway.xml`,
  `ignition.conf`, `modules.json` — was silently never committed, and the tree had to hide it.
  Staging is now plain git. Upgrading adds explicit rules for those paths, so **the committed set
  does not change**; tick any of them on the Git Ignore tab to start versioning it.
- **The Git Ignore tree shows the whole data directory**, rooted where the repository is, with
  `config/` expanded. Every row can be ticked.
- **No more strikethrough.** A path that is not versioned is unticked and greyed, with its reason
  on the right.
- **Saving on the Git Ignore tab commits straight away.** The auto-committer only listens for
  config *resource* changes, so a `.gitignore` edit used to sit uncommitted until something else
  happened to commit.

### Fixed
- **A fine-grained token's scope is no longer guessed from `GET /user/repos`.** That endpoint
  returns every public repository the account can see plus the granted private ones, so a token
  scoped to one repository was reported as fifteen. The row now reports only what GitHub will
  vouch for, and **Reaches** carries the measured truth.

## [3.7.0] - 2026-09-21

### Changed
- **A delivery is only ever what someone chose.** 3.5.0's upgrade handed Runner — release to every
  project folder on a gateway whose runner was on, including projects that were not versioned, so
  the Projects tab showed a delivery for everything. Upgrading to 3.7.0 clears them once — every
  runner mode forgotten, every scheduled sync disabled, reported in the gateway log and on the Logs
  tab — and nothing is ever assigned again. Re-pick each project's delivery on the Projects tab
  after upgrading. Gateways that never had runner access configured are left alone: nothing could
  have been assigned on them.
- **The Git Ignore tab says what it is showing.** A legend for the marks the tree uses, and every
  struck-through row now gives its reason — "excluded here", "excluded by <rule>", "excluded with
  its parent" or "everything inside is excluded". Before, a row excluded by its own `.gitignore`
  line showed no reason at all. The heading matches the tab, and an empty folder says so.
- **Credentials**: the "Repositories" sub-heading is gone, Add credential sits in the page head.

### Added
- **The module version is on the page**, under the title on every tab, so a screenshot says which
  build a gateway runs.
- **Credential scope.** Each GitHub credential says what its token may touch at all: a classic
  token reports its scopes and is flagged as reaching every repository its account can, a
  fine-grained token reports the repositories it was granted. That is a different question from
  Reaches, which is what this gateway uses it for.
- **The check reports failures.** A refused or unreachable host, a timeout or a broken remote is
  shown in place of the expiry instead of the same dash that means "not asked", Check gives a
  toast and a per-row spinner, the time of the last check is shown, and network calls time out
  after 10 seconds. An unused credential reads "No project uses this yet" and its token is still
  tested.

## [3.6.0] - 2026-09-18

### Added
- **Credentials show when a token expires and what it can reach.** For each repository
  credential: the GitHub token's expiry date (amber within 14 days, red once expired or refused),
  the account it belongs to, and each project — and the gateway config repository — it can read
  or push. A fine-grained token carries none of this, so the gateway asks the host: on save, when
  attached to a project, daily, and on **Check**. The push test only asks whether a push would be
  accepted; nothing is sent.
- **Edit a credential in place** — replace an expiring token, or change the username or host,
  without re-pointing the projects that use it. A blank secret keeps the stored one.
- **The Projects tab flags a credential problem** in the State column: expired, refused, cannot
  read the remote, or expiring within 14 days.

## [3.5.0] - 2026-09-18

### Changed
- **Delivery is set per project, on the Projects tab, and is opt-in.** Each project has one
  delivery — Off, Runner release, Runner repo updates, Sync pull or Sync replace — chosen in its
  drawer with Save. **Off refuses the runner**: a release or repo update for a project not set to
  it, or for a project that does not exist on the gateway, is answered 409. Before, the tick box
  and token let a workflow install any project name it sent, including creating new ones.
- **Upgrading keeps existing deliveries working:** once, projects the runner could already
  deliver to (runner on, no choice made, no sync) are set to Runner release.
- **The Automation tab is gone.** Runner access (the tick box and token) is a section of
  Credentials; the event log is the Logs tab. The runner test command and the push-or-pull
  comparison are in the README. History is now **Gateway History**, Excluded files **Git Ignore**.
- Every edit opens a side drawer with Save and Cancel; nothing saves on a click.

### Removed
- The runner setup checklist, the workflow scan and "runner last called".

## [3.4.2] - 2026-09-18

### Fixed
- **Ignition's own reformatting no longer counts as local changes.** Importing a project makes
  the gateway rewrite every `project.json` and `resource.json` in its own format — no final
  newline, an apostrophe escaped, `"parent": ""` added, a resource's file list reordered — with
  identical content. A repository written by anything but Ignition then showed every such file
  modified straight after a clone or sync: the Projects tab reported dozens of uncommitted
  changes, Replace reported overwriting them, and **Pull refused every sync from then on**. A
  modified JSON file whose parsed content equals the commit's is now not a change, and Pull
  restores those files before it merges.

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
