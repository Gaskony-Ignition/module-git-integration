# Internals reference

Reference tables for the gateway REST API, the persisted resource types, the
manager classes, and the Designer popups. `CLAUDE.md` covers standing
instructions and gotchas; this file is the lookup material it links to.

## REST routes

Mounted in `GatewayHook.mountRouteHandlers`, under `/data/git-config/…`, not
the RPC interface. Reads require `PermissionType.READ`, mutations `WRITE`,
and mutations need an `X-CSRF-Token` fetched from `/csrf`. The one exception
are the two runner routes — see Delivery below.

| Area | Method + path |
| --- | --- |
| Config-as-code | `GET /status` (also carries the module version, shown under the page title), `/history`, `/commit-files`, `/file-diff`, `/remote`, `/tree`, `/tree-search`, `/ignore`, `/branch-graph` (`?project=` or `?repo=config`, `&limit=`) |
| Config-as-code | `POST /restore`, `/init`, `/deinit`, `/secret-providers` (lists them; WRITE), `/secret-ref` (the config credential's provider and secret names; WRITE), `/remote`, `/remote-remove`, `/remote-test`, `/push`, `/ignore`, `/update-from-remote`, `/config-delivery` (follow a branch: off, replace, or in Combined pull/runner; branch, interval), `/config-sync-now`, `/layout` (`separate`/`combined`, `restoreHistory`), `/branch-checkout` (`project` or `repo:"config"`, `branch`) |
| Projects & credentials | `GET /projects`, `/credentials` |
| Projects & credentials | `POST /project-init`, `/project-remote`, `/project-credential`, `/project-images`, `/project-snapshot-images`, `/credentials`, `/credential-remove`, `/credential-check` |
| Delivery & logs | `GET /events`, `/runner` |
| Delivery & logs | `POST /delivery` (`ignitionUser` only the caller or the owner already set; `keepProjectProperties`, absent = unchanged), `/sync-now` (and `/config-sync-now`: `outcome` ok, warning or failed), `/events-clear`, `/runner`, `/runner-sync`, `/runner-release` |
| Modes & policy (4.1.0) | `POST /policy`, `/mode-override` (Create Override: `mode`, `from`), `/mode-reset` (Remove Override: `mode`, blank = active); each takes `type` policy, remote, runner, sync + `project`, credential + `id`/`credType`. `GET /definition` (same keys + `collection`) answers one collection's definition in the shape of the record's own GET, plus `defined`. `GET /status` carries `policy` and `modes` |
| 4.4.0 | `POST /project-add-peek` (branches, default, suggested name), `/project-add` (`name`, `url`, `branch`, credential, `delivery`), `/config-fetch`, `/config-pull`, `/config-join`, `/abort-merge` (4.4.3: undoes a merge left open in the gateway repository; `GET /status` carries `midOperation` while one is); `/deinit` takes `removeRemote`, `/layout` `archiveLeftovers`; `GET /remote` carries `behind` and `unrelated` |
| Modes (4.5.0) | `POST /override-audit` (`type`, `action` create/move/remove, `from`, `to`): the audit row after the platform's own Create Override / Move Definition / Remove Override, written only when the record's definitions show it happened. `GET /projects` carries `syncId` (the sync record's resource name) |
| Audit (4.4.0) | `GET /audit` (on/off, profile, `gatewayHasProfile`, the profiles), `POST /audit` (`enabled`, `profile`; blank follows the gateway's) |
| Branch names (4.2.0) | `POST /branch-rename-master` (`project` or `repo:"config"`); `GET /remote` and `/projects` carry `renameMaster` when it applies |

`POST /remote`, `/credentials`, `/project-init`, `/delivery`, `/runner` and `/policy` take
`?saveTo=core|<any mode>` in the query string (the handlers read the body themselves): every
record they save lands in that collection, and the record they load is that collection's
definition (`ModeStore.readingFrom`), so a blank secret keeps that mode's. Left out, a record
stays where it is defined. A
`PolicyGate.Refused` answers 403.

`POST /runner-sync` and `/runner-release` are the routes with no permission check and no CSRF
token — a GitHub Actions workflow step has neither a gateway session nor a
way to obtain one. It authenticates itself instead: `AccessControlStrategy.OPEN_ROUTE`,
an `Authorization: Bearer` token compared with `MessageDigest.isEqual`, a
64 KB body cap, and a 404 until a token exists. The token is generated
gateway-side, stored encrypted, and returned exactly once.

## Persisted resource types

Ten types on the 8.3 resource/config system; each `*Record` is a mutable
DTO façade over a resource handler whose config is a nested Java `record`. The sync, SSH key and
HTTPS credential metas come from `ModeStore.overridable` (`ResourceActionSet.DEFAULT` plus a route
delegate) over a `NamedResourceHandler`; the policy, config remote and runner (4.5.0) from
`ModeStore.singleton` (the same plus `.singleton()`) over a `SingletonResourceHandler`, stored at
`<collection>/com.operametrix.ignition.git/<type>/`, which is what the platform's
`resources/singleton` route and its Resource Definitions panel need. The remote and runner metas
carry a default, so the platform writes a blank one to core at start-up (a blank remote URI reads
as none); the policy's does not, so nothing can be written over one not yet moved
(`GitPolicyRecord.ensureCore` writes it). Saves go through `ModeStore.save`/`saveSingleton`, which
write to the route's `saveTo` collection — creating the override there, or building the modify
itself when the target is overridden above it — and `ModeStore.removeOverride` deletes one mode's
copy. `records.legacy.SingletonMigration` moves 4.4's named copies (`git-policy/policy/`,
`git-config-remote/1/`, `git-runner/runner/`) up a level in every collection at start-up, before
any handler starts, then requests a config scan; a failed move leaves the policy locked.

| Record | Holds |
| --- | --- |
| `GitProjectsConfigRecord` | Project registration marker + `imagePrefix` (the one image-store folder this project versions; empty = none). Remote data lives only in `.git/config`. |
| `GitReposUsersRecord` | Project↔user registration marker; commit email comes from the Ignition user profile. |
| `GitRemoteCredentialsRecord` | Per (project, user, remote) `SshKeyId`/`HttpsCredentialId` FK. |
| `GitUserSshKeyRecord` | User-level SSH key; secret is a `SecretConfig` (embedded-encrypted or Secret-Provider reference). Shared across projects. |
| `GitConfigRemoteRecord` | Gateway-level singleton: the data-dir config repo's remote (URI, branch, credential FK). Manual push only. |
| `GitSyncRecord` | Per-project sync: enabled, Pull/Replace, remote, branch, interval, authenticating user. Kept disabled for a runner repo update's branch and credential. |
| `GitRunnerRecord` | Gateway-level singleton: runner on/off, bearer token (`SecretConfig`), per-project runner mode (release/repo — absent is Off), and `optIn` (the 3.5.0 migration has run). |
| `GitUserHttpsCredentialRecord` | User-level HTTPS credential (host, username, encrypted password). Auth resolves via the FK, never a pattern match; the host binds the credential (4.3.0: always — a credential with no host goes nowhere). |
| `GitAuditRecord` (4.4.0) | Gateway-level singleton `audit`: on/off and the audit profile (blank: the gateway's own). Not per mode, not versioned. |
| `GitPolicyRecord` (4.1.0) | Gateway-level singleton `policy`, fields in the pure `GitPolicy` record (boxed: absent = default). The only record versioned with the config; written to core with the default at startup. |

A one-time `records.legacy.GitLegacyImporter` migrates the old SimpleORM
tables into resources on first 8.3 startup, and `records.legacy.RetiredResourceCleanup`
deletes the resource types left over from removed features, both idempotent.

## Manager classes (gateway)

| Class | Responsibility |
| --- | --- |
| `GitManager` | Core JGit operations: clone, fetch, pull, push, commit/amend, status, branch, checkout, diff, history, discard, revert, remote list. Every remote op requires an explicit credential FK — there is no other auth path. |
| `DataDirGitManager` (no staging scope — `.gitignore` alone decides, and its `/*` + `!/config/` rules keep it to `config/` by default; `denyRootOnce` adds them to an older repository and untracks what they exclude, guarded by the `.git-module-root-denied` marker) | The data-directory (config-as-code) repo: init, status, auto-commit, restore, push, remote, and the exclusion tree — `listTree` one level at a time, `searchTree` a breadth-first walk capped at 40,000 entries and 200 matches. Serialised by one static `DATA_DIR_LOCK`. |
| `ConfigAutoCommitter` | Listens for config changes and commits them; the only live notification surface (per-resource listeners on the config collection are never called). |
| `GitProjectManager` / `GitTagManager` / `GitThemeManager` / `GitImageManager` | Per-project resource import, and gateway-resource snapshot (tags/themes/images) into the project tree. |
| `RepoLocator` / `ProjectScope` / `LayoutSwitch` (4.0.0) | The repository layout, unit tested with no gateway context. `RepoLocator` maps a project to `(root, prefix)`: Separate `(projects/<name>, "")`, Combined `(dataDir, "projects/<name>/")`; the layout lives in the data-dir repo's `.git/config` (`gitmodule.layout.mode`), absent = Separate. Every project RPC goes through `GitManager.repoFor`. `ProjectScope` is what changes meaning under a prefix: status limited by pathspec and stripped (a path outside the project comes back with a leading `/`, which `toRepoPath` maps back — conflicts use it), commit with `--only` (amend refused if HEAD touched anything outside), history by path, and a revert refused when the commit changed anything outside the project, undone for the project's paths only on conflict. A project folder with its own `.git` in Combined is refused by name, never hidden. `LayoutSwitch` moves between layouts (see README); in Combined, project and config writes share `DataDirGitManager.lock()`. Push (page and Designer) uses `ProjectScope.pushTarget`: the checked-out branch's upstream, else the configured branch for the home branch (the configured one, or the only local branch), else its own name, recorded as upstream after the first push. A Designer pull in Combined commits config's pending changes, then refuses while it would change uncommitted files outside the project. Sync — replace keeps an untracked `projects/<name>/` (a `warning` on the Logs tab). |
| `BranchGraph` / `BranchSwitch` (4.0.0) | Behind the Branches views, no gateway context so both are unit tested. `BranchGraph` walks every local and remote-tracking ref and tag (topological, limit 200) and assigns lanes on the server: a column per commit and, per parent edge, the column its line runs down (reserved when the child is placed, freed when the parent is reached), so the page only draws. Per local branch it adds `BranchHygiene.Info` plus behind-upstream. With a path prefix (Combined project view) commits are kept but flagged `touches=false` when they leave the prefix, judged against the first parent. `BranchSwitch` checks out a local branch, or creates a tracking one from `origin/x`; it never stashes. It refuses with the file list when the tree has uncommitted work (Combined and the config repository: everything except `config/`, which is committed first), and refuses when an enabled sync or delivery follows a different branch. The route resolves the repository through `GitManager.repoFor`, then loads the result like a pull does (project import and scan, or config scan plus all projects) and logs a `checkout` event. `GET /projects` also carries each project's `ahead`/`behind`, asked once per repository. |
| `PullGuard` / `MasterRename` / `LayoutCheck` (4.2.0) | Pure, unit tested. `PullGuard`: after a pruning fetch, the current branch's gone upstream (`UPSTREAM_GONE:` text the Designer turns into Switch to main and pull), the branch to go back to (`<remote>/HEAD`, configured, main, master), and plain sentences for JGit failures (original after `\n\nDetails: `). `MasterRename`: new repositories start on the configured branch or main; a lone `master` behind a remote `main` is renamed on request. `LayoutCheck`: before the gateway repository pushes or pulls, a branch with no `config/` at the top, or unrelated history, is refused. |
| `PolicyGate` (4.1.0) | Every policy question: `check(Action)`, `checkDelivery`, `checkHost`, the refusal texts, `credentialMismatch` (credential host vs URL host), `refusals()` for the Designer's `getPolicyRefusals` RPC and the `Gateway-Mode` trailer. Pure decisions, unit tested. `GitManager.setAuthentication*` call `checkHost` and the host match before attaching any credential, so every remote op is covered. |
| `OriginSync` (4.1.0) | Sets the gateway repository's `origin` from the effective config remote record (URL, no push URL, upstreams moved when the branch changed; applied branch in `gitmodule.origin.branch`). `DataDirGitManager.authOrigin` runs it before every config push and fetch, and `setAuthentication` before a Combined `origin` op. |
| `SafePaths` / `RemoteUrls` / `RepoHardening` (4.3.0) | `SafePaths`: project names (`[A-Za-z0-9_-]+`), repo-relative paths (no absolute, `..` or `.git`, symlinks followed and kept inside), 5 MB regular-file reads, error text without install folders. `RemoteUrls`: https/ssh only, no URL passwords, no loopback/link-local targets, unless the policy's `allowInsecureRemotes`; called from `PolicyGate.checkHost`. `RepoHardening`: `harden` (no `http.sslVerify`, `core.symlinks=false`, `http.followRedirects=false`) on every repository created, and at startup on existing ones, plus URL passwords stripped, legacy plaintext SSH keys encrypted, label-only HTTPS credentials bound to their remotes' one host, and the `IgnoreRules.DENY` block kept last in `.gitignore`. |
| `PolicyWatch` (4.3.0) | Compares the effective policy with the last one seen (`.git-module-policy-seen` in the data dir) on every config change and at startup; `GitPolicy.weakerThan` names what got looser, logged as a warning event. `GitPolicyRecord.get` returns `GitPolicy.LOCKED` for a policy it cannot read. |
| `IgnoreRules` | The `.gitignore` template and the Git ignore tab's rule matching (`decide`, `matchRule`, `isOwnLine`), pure so it is unit tested. `versionPolicy` rewrites the 3.10.0 records line into the 4.1.0 pair; `DataDirGitManager.versionPolicyOnce` applies it once at startup (marker `.git-module-policy-versioned`). |
| `ConfigSync` | The config repo's Delivery (off, or Sync — replace; in Combined also Sync — pull and Runner — repo updates via `DataDirGitManager.pullFromBranch`, kept but inactive in Separate): settings in `.git/config` section `gitmodule.configsync` (not a resource, which would be versioned and overwritten by the branch), run from the `SyncScheduler` timer; calls `DataDirGitManager.replaceFromBranch`, which holds `ConfigAutoCommitter` and records the applied tip in `refs/git-module/config-synced`. `GET /remote` carries `delivery` and the last run. |
| `BranchHygiene` | Branch classification and delete rules on a plain JGit `Git`, no gateway context (unit tested). Every `FetchCommand` except the single-branch init clone sets `setRemoveDeletedRefs(true)`, so a branch deleted on the remote loses its `refs/remotes/*` ref and its local twin reads **gone** (upstream configured, ref missing). Merged is measured against the current branch's upstream, else HEAD; ahead is against the branch's own upstream, else all remote refs. Protected: the current branch and the branch an enabled `GitSyncRecord` follows (`sync`) — the Projects drawer's delivery branch is stored on that record, so there is no other; in Combined, instead, the gateway delivery's `ConfigSync` branch (`delivery`). |
| `SyncScheduler` | Per-project scheduled fetch, then fast-forward (Pull) or reset to the branch (Replace). |
| `CredentialCheck` (3.7.0) | Per credential: GitHub token expiry (the `github-authentication-token-expiration` header on `GET /user`; 401 = rejected), and for each remote using it, read (fetch advertisement) and push (receive-pack advertisement — refused there without write, so nothing is sent). Scope: a classic token's `x-oauth-scopes` header (every repository its account can reach) or a fine-grained token's granted repositories. Every failure is kept and shown rather than logged at debug. In memory; startup + 30 s, daily, on add, on attach, on **Check**. |
| `RunnerAuth` / `RunnerTrigger` / `ReleaseReceiver` | Runner token check; the opt-in guard (`RunnerTrigger.refuse`); the repo-update and release routes. |
| `IgnitionReformat` | Counts local changes by parsed JSON content, ignoring Ignition's rewrite of imported files. |
| `GitAudit` (4.4.0) | One audit row per essential action (push, pull, delivery installed, branch/layout switch, restore, revert, remote change, policy, credential and override add/remove), tagged with the Designer's host and context when an RPC made it, queued to one background thread; a failing profile is logged once. Call sites are the routes and RPCs that complete those actions, never pollers or the auto-committer. |
| `WebFlowSignature` / `GitHubMerged` (4.4.0) | "Only deliver commits merged on GitHub". `WebFlowSignature` is pure (unit tested): the commit split into payload and `gpgsig` as git signs it, a v4 RSA OpenPGP signature checked with JDK crypto (type 0x00, SHA-256/384/512, issuer and creation time from hashed subpackets, key expiry and revocation from the bundled key block). `GitHubMerged` loads the bundled `github-web-flow.asc` (or `-Dgitmodule.trustedSigningKeys`, for the suite) and is asked by `SyncScheduler`, `replaceFromBranch` and `pullFromBranch` before a tip is installed; one Logs warning per refused tip. |
| `GitEvents` | Synchronous, log-only ring buffer (50 entries) behind the Logs tab; never throws or blocks the operation that fired it. |

## Designer popups

Swing `JDialog`s parented to the Designer frame; callbacks live in `GitActionManager`.

| Popup | Purpose |
| --- | --- |
| `CommitPopup` | Pick changes + message; amend; double-click → diff. |
| `DiffViewerPopup` | Side-by-side line diff, reused by merge-conflict and commit-detail views. |
| `CommitDetailPopup` | Files in one commit, checkout/revert, historical diff. |
| `MergeConflictPopup` | Per-file accept ours/theirs, global accept-all/abort/complete. |
| `PullPopup` / `PushPopup` / `FetchPopup` | Remote selector; single-remote acts immediately. |
| `BranchPopup` | Local/remote branch lists with `[gone]`/`[merged]`/`↑N` marks, create/checkout/safe delete, **Clean up…** (fetch, then a ticked list of gone + merged). |
| `CreateBranchPopup` | Branch name, always created from HEAD. |
| `InitRepoPopup` | Wizard: remote (URI + credential) or local-only init. |
| `RemotesPopup` | Named-remote list/form, picks a saved credential. |
| `UserCredentialsPopup` | User-level SSH keys + per-host HTTPS credentials. |
| `InitProgressDialog` | Modal progress for long operations, keeps the EDT responsive. |

## Git ignore tree rules

Re-including under a glob appends a negation rather than editing the glob:
deleting `**/logs` to recover one file would version a gigabyte. Nothing under an
excluded directory can be re-included, so that row is read-only. A folder's tick
is the roll-up of its children, since a rule can exclude a directory's contents
but not the directory itself. Saving commits.

The module's own `.gitignore` lines (template, root and records rules, the
Combined rules, the tab's ticks) sit between `# >>> Git Integration (managed …)`
and `# <<< Git Integration`; every module write (`IgnoreRules.editManaged`/`split`/
`join`) rewrites only that block, and the tab adds its lines at its end. A line
outside it is never moved or removed, except that ticking a path removes a line
naming exactly that path wherever it is (otherwise the tick could not take
effect); globs outside are never edited. The deny block (`IgnoreRules.DENY`) has
its own markers and is moved back to the very end on every write, since git's
last match wins. 4.5.0 wraps an older file once at start-up
(`ignoreBlocksOnce`, marker `.git-module-ignore-blocks`, commits `.gitignore`
only): lines exactly as the module wrote them go into the block at the first
one's place, everything else keeps its order outside, and a file with none gets
an empty block at its end. Projects' own repositories get no `.gitignore` from
the module.

