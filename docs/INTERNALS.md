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
| Config-as-code | `GET /status`, `/history`, `/commit-files`, `/file-diff`, `/remote`, `/secret-providers`, `/tree`, `/ignore` |
| Config-as-code | `POST /restore`, `/init`, `/deinit`, `/remote`, `/remote-remove`, `/remote-test`, `/push`, `/ignore`, `/update-from-remote` |
| Projects & credentials | `GET /projects`, `/credentials` |
| Projects & credentials | `POST /project-init`, `/project-remote`, `/project-credential`, `/project-images`, `/project-snapshot-images`, `/credentials`, `/credential-remove`, `/credential-check` |
| Delivery & logs | `GET /events`, `/runner` |
| Delivery & logs | `POST /delivery`, `/sync-now`, `/events-clear`, `/runner`, `/runner-sync`, `/runner-release` |

`POST /runner-sync` and `/runner-release` are the routes with no permission check and no CSRF
token — a GitHub Actions workflow step has neither a gateway session nor a
way to obtain one. It authenticates itself instead: `AccessControlStrategy.OPEN_ROUTE`,
an `Authorization: Bearer` token compared with `MessageDigest.isEqual`, a
64 KB body cap, and a 404 until a token exists. The token is generated
gateway-side, stored encrypted, and returned exactly once.

## Persisted resource types

Eight types on the 8.3 resource/config system; each `*Record` is a mutable
DTO façade over a `NamedResourceHandler` whose config is a nested Java
`record`.

| Record | Holds |
| --- | --- |
| `GitProjectsConfigRecord` | Project registration marker + `imagePrefix` (the one image-store folder this project versions; empty = none). Remote data lives only in `.git/config`. |
| `GitReposUsersRecord` | Project↔user registration marker; commit email comes from the Ignition user profile. |
| `GitRemoteCredentialsRecord` | Per (project, user, remote) `SshKeyId`/`HttpsCredentialId` FK. |
| `GitUserSshKeyRecord` | User-level SSH key; secret is a `SecretConfig` (embedded-encrypted or Secret-Provider reference). Shared across projects. |
| `GitConfigRemoteRecord` | Gateway-level singleton: the data-dir config repo's remote (URI, branch, credential FK). Manual push only. |
| `GitSyncRecord` | Per-project sync: enabled, Pull/Replace, remote, branch, interval, authenticating user. Kept disabled for a runner repo update's branch and credential. |
| `GitRunnerRecord` | Gateway-level singleton: runner on/off, bearer token (`SecretConfig`), per-project runner mode (release/repo — absent is Off), and `optIn` (the 3.5.0 migration has run). |
| `GitUserHttpsCredentialRecord` | User-level HTTPS credential (host pattern, username, encrypted password). `HostPattern` is a picker label only — auth resolves via the FK, never a pattern match. |

A one-time `records.legacy.GitLegacyImporter` migrates the old SimpleORM
tables into resources on first 8.3 startup, and `records.legacy.RetiredResourceCleanup`
deletes the resource types left over from removed features, both idempotent.

## Manager classes (gateway)

| Class | Responsibility |
| --- | --- |
| `GitManager` | Core JGit operations: clone, fetch, pull, push, commit/amend, status, branch, checkout, diff, history, discard, revert, remote list. Every remote op requires an explicit credential FK — there is no other auth path. |
| `DataDirGitManager` | The data-directory (config-as-code) repo: init, status, auto-commit, restore, push, remote. Serialised by one static `DATA_DIR_LOCK`. |
| `ConfigAutoCommitter` | Listens for config changes and commits them; the only live notification surface (per-resource listeners on the config collection are never called). |
| `GitProjectManager` / `GitTagManager` / `GitThemeManager` / `GitImageManager` | Per-project resource import, and gateway-resource snapshot (tags/themes/images) into the project tree. |
| `SyncScheduler` | Per-project scheduled fetch, then fast-forward (Pull) or reset to the branch (Replace). |
| `CredentialCheck` | Per credential: GitHub token expiry (the `github-authentication-token-expiration` header on `GET /user`; 401 = rejected), and for each remote using it, read (fetch advertisement) and push (receive-pack advertisement — refused there without write, so nothing is sent). In memory; startup + 30 s, daily, on add, on attach, on **Check**. |
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
| `BranchPopup` | Local/remote branch lists, create/checkout/delete. |
| `CreateBranchPopup` | Branch name, always created from HEAD. |
| `InitRepoPopup` | Wizard: remote (URI + credential) or local-only init. |
| `RemotesPopup` | Named-remote list/form, picks a saved credential. |
| `UserCredentialsPopup` | User-level SSH keys + per-host HTTPS credentials. |
| `InitProgressDialog` | Modal progress for long operations, keeps the EDT responsive. |
