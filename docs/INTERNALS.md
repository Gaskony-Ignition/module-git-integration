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
| Config-as-code | `GET /status` (also carries the module version, shown under the page title), `/history`, `/commit-files`, `/file-diff`, `/remote`, `/secret-providers`, `/tree`, `/tree-search`, `/ignore`, `/branch-graph` (`?project=` or `?repo=config`, `&limit=`) |
| Config-as-code | `POST /restore`, `/init`, `/deinit`, `/remote`, `/remote-remove`, `/remote-test`, `/push`, `/ignore`, `/update-from-remote`, `/config-delivery` (follow a branch: off, replace, or in Combined pull/runner; branch, interval), `/config-sync-now`, `/layout` (`separate`/`combined`, `restoreHistory`), `/branch-checkout` (`project` or `repo:"config"`, `branch`) |
| Projects & credentials | `GET /projects`, `/credentials` |
| Projects & credentials | `POST /project-init`, `/project-remote`, `/project-credential`, `/project-images`, `/project-snapshot-images`, `/credentials`, `/credential-remove`, `/credential-check` |
| Delivery & logs | `GET /events`, `/runner` |
| Delivery & logs | `POST /delivery`, `/sync-now`, `/events-clear`, `/runner`, `/runner-sync`, `/runner-release` |
| Modes & policy (4.1.0) | `POST /policy`, `/mode-reset` (`type` policy, remote, runner, sync + `project`, credential + `id`/`credType`); `GET /status` carries `policy` (mode, from, fields, `refused` texts) |

`POST /remote`, `/credentials`, `/project-init`, `/delivery`, `/runner` and `/policy` take
`?saveTo=core|<active mode>` in the query string (the handlers read the body themselves): every
record they save lands in that collection. Left out, a record stays where it is defined. A
`PolicyGate.Refused` answers 403.

`POST /runner-sync` and `/runner-release` are the routes with no permission check and no CSRF
token — a GitHub Actions workflow step has neither a gateway session nor a
way to obtain one. It authenticates itself instead: `AccessControlStrategy.OPEN_ROUTE`,
an `Authorization: Bearer` token compared with `MessageDigest.isEqual`, a
64 KB body cap, and a 404 until a token exists. The token is generated
gateway-side, stored encrypted, and returned exactly once.

## Persisted resource types

Nine types on the 8.3 resource/config system; each `*Record` is a mutable
DTO façade over a `NamedResourceHandler` whose config is a nested Java
`record`. The config remote, sync, runner, SSH key, HTTPS credential and policy
metas come from `ModeStore.overridable` (`ResourceActionSet.DEFAULT` plus a route
delegate), so a deployment mode can override them; saves go through
`ModeStore.save`, which writes to the route's `saveTo` collection — creating the
override there, or building the modify itself when the target is overridden
above it — and `ModeStore.reset` deletes the active mode's copy.

| Record | Holds |
| --- | --- |
| `GitProjectsConfigRecord` | Project registration marker + `imagePrefix` (the one image-store folder this project versions; empty = none). Remote data lives only in `.git/config`. |
| `GitReposUsersRecord` | Project↔user registration marker; commit email comes from the Ignition user profile. |
| `GitRemoteCredentialsRecord` | Per (project, user, remote) `SshKeyId`/`HttpsCredentialId` FK. |
| `GitUserSshKeyRecord` | User-level SSH key; secret is a `SecretConfig` (embedded-encrypted or Secret-Provider reference). Shared across projects. |
| `GitConfigRemoteRecord` | Gateway-level singleton: the data-dir config repo's remote (URI, branch, credential FK). Manual push only. |
| `GitSyncRecord` | Per-project sync: enabled, Pull/Replace, remote, branch, interval, authenticating user. Kept disabled for a runner repo update's branch and credential. |
| `GitRunnerRecord` | Gateway-level singleton: runner on/off, bearer token (`SecretConfig`), per-project runner mode (release/repo — absent is Off), and `optIn` (the 3.5.0 migration has run). |
| `GitUserHttpsCredentialRecord` | User-level HTTPS credential (host pattern, username, encrypted password). Auth resolves via the FK, never a pattern match; since 4.1.0 a host pattern that names a host (has a dot) also binds the credential to it. |
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
| `PolicyGate` (4.1.0) | Every policy question: `check(Action)`, `checkDelivery`, `checkHost`, the refusal texts, `credentialMismatch` (credential host vs URL host), `refusals()` for the Designer's `getPolicyRefusals` RPC and the `Gateway-Mode` trailer. Pure decisions, unit tested. `GitManager.setAuthentication*` call `checkHost` and the host match before attaching any credential, so every remote op is covered. |
| `OriginSync` (4.1.0) | Sets the gateway repository's `origin` from the effective config remote record (URL, no push URL, upstreams moved when the branch changed; applied branch in `gitmodule.origin.branch`). `DataDirGitManager.authOrigin` runs it before every config push and fetch, and `setAuthentication` before a Combined `origin` op. |
| `IgnoreRules` | The `.gitignore` template and the Git Ignore tab's rule matching (`decide`, `matchRule`, `isOwnLine`), pure so it is unit tested. `versionPolicy` rewrites the 3.10.0 records line into the 4.1.0 pair; `DataDirGitManager.versionPolicyOnce` applies it once at startup (marker `.git-module-policy-versioned`). |
| `ConfigSync` | The config repo's Delivery (off, or Sync — replace; in Combined also Sync — pull and Runner — repo updates via `DataDirGitManager.pullFromBranch`, kept but inactive in Separate): settings in `.git/config` section `gitmodule.configsync` (not a resource, which would be versioned and overwritten by the branch), run from the `SyncScheduler` timer; calls `DataDirGitManager.replaceFromBranch`, which holds `ConfigAutoCommitter` and records the applied tip in `refs/git-module/config-synced`. `GET /remote` carries `delivery` and the last run. |
| `BranchHygiene` | Branch classification and delete rules on a plain JGit `Git`, no gateway context (unit tested). Every `FetchCommand` except the single-branch init clone sets `setRemoveDeletedRefs(true)`, so a branch deleted on the remote loses its `refs/remotes/*` ref and its local twin reads **gone** (upstream configured, ref missing). Merged is measured against the current branch's upstream, else HEAD; ahead is against the branch's own upstream, else all remote refs. Protected: the current branch and the branch an enabled `GitSyncRecord` follows (`sync`) — the Projects drawer's delivery branch is stored on that record, so there is no other; in Combined, instead, the gateway delivery's `ConfigSync` branch (`delivery`). |
| `SyncScheduler` | Per-project scheduled fetch, then fast-forward (Pull) or reset to the branch (Replace). |
| `CredentialCheck` (3.7.0) | Per credential: GitHub token expiry (the `github-authentication-token-expiration` header on `GET /user`; 401 = rejected), and for each remote using it, read (fetch advertisement) and push (receive-pack advertisement — refused there without write, so nothing is sent). Scope: a classic token's `x-oauth-scopes` header (every repository its account can reach) or a fine-grained token's granted repositories. Every failure is kept and shown rather than logged at debug. In memory; startup + 30 s, daily, on add, on attach, on **Check**. |
| `RunnerAuth` / `RunnerTrigger` / `ReleaseReceiver` | Runner token check; the opt-in guard (`RunnerTrigger.refuse`); the repo-update and release routes. |
| `IgnitionReformat` | Counts local changes by parsed JSON content, ignoring Ignition's rewrite of imported files. |
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

## Git Ignore tree rules

Re-including under a glob appends a negation rather than editing the glob:
deleting `**/logs` to recover one file would version a gigabyte. Nothing under an
excluded directory can be re-included, so that row is read-only. A folder's tick
is the roll-up of its children, since a rule can exclude a directory's contents
but not the directory itself. Saving commits.

