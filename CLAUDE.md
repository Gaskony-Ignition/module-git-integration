# CLAUDE.md

Standing instructions and gotchas. `docs/INTERNALS.md` has the REST routes,
resource types, manager classes and Designer popups.

## Project overview

A Java module for Ignition 8.3 that embeds a Git client into the Designer:
commit, push, pull with conflict resolution, branches, and snapshotting
gateway resources (tags/themes/images) into the project. It also versions the
gateway **data directory** (config-as-code) in a separate repo, via a gateway
page (Platform → System → "Versioning") with auto-commit, history and restore.
Built by AXONE-IO and Operametrix; this is Gaskony's fork.

## Build commands

```bash
rm -rf build/moduleContent && ./gradlew build   # -> build/GitIntegration-<ver>.modl
```

Clear `build/moduleContent` first: after a version bump it keeps the old
jars and the `.modl` ships both. Prettier violations fail webpack — run
`node_modules/.bin/prettier --write "src/**/*.{ts,tsx}"` in `web-ui/`.

Tests: `./gradlew test` covers the pure branch, layout, Git Ignore, policy and
origin logic (`gateway/src/test`); the gateway side is `test/regression/run.sh`
(docs/TESTING.md); the Designer is checked by hand in both layouts.

## Architecture

Gradle multi-module, following the Ignition Module SDK pattern:

```text
common    (scope: DG)   — RPC interface + abstract delegation base
designer  (scope: D)    — Designer UI: dockable panels, popups, status bar
gateway   (scope: G)    — all git operations + persistence + config-as-code REST routes
web-ui    (no scope)    — React gateway page, bundled into the gateway jar
```

No Vision client scope.

## Gotchas

- **RPC implementation must implement `GitScriptInterface` directly.** 8.3's
  `RpcDelegate` discovers `@RpcInterface` only on the concrete class's direct
  interfaces, with no superclass walk.
- **A custom `ProtoRpcSerializer` registers a `Dataset`/`BasicDataset`
  Java-serialization adapter.** The default serializer has no `Dataset`
  support, so without it every `Dataset`-returning RPC round-trips empty with
  no error.
- **After any gateway-side project mutation (pull, checkout, init, snapshot),
  the Designer must call `GitBaseAction.pullProjectFromGateway()`** — it
  discards stale edits via `DesignableProject.discardChanges`, then reflectively
  calls `IgnitionDesigner.updateProject()`, or gateway changes never show.
- **The config-as-code repo is rooted at the data directory and `.gitignore` is
  the only thing deciding what it versions** (never reintroduce a
  staging scope; a file nothing ignored was silently never committed).
  `/*` + `!/config/` keep out other modules' runtimes (CPython, Chromium).
- **`ConfigAutoCommitter` (a `ResourceCollectionListener`) is the only live
  config-change notification**; per-resource listeners are never notified and a
  plain file write (`.gitignore`) is not one, so that path commits itself.
  Events within 2s coalesce; `commitLeftovers()` sweeps offline changes.
- **Restore resets the working tree to the target commit and keeps HEAD on the
  branch** (unlike the detaching checkout), then applies it by settings rescan.
- **`GITIGNORE_LINES` must ignore the SQLite sidecars `*-wal`/`*-shm`**, or
  `git add .` races the tag value store and dies with `FileNotFoundException`,
  so init never completes on a live gateway.
- **Git Ignore tree**: excluding a tracked path also runs `git rm --cached`;
  re-including under a glob appends a negation, never edits it. Rules in
  `docs/INTERNALS.md`.
- **Change badges are a `DotBorder`, not `addBadge`** (the renderer stops painting
  added badges after the first commit).
- **The platform's `TextInput`/`SelectInput`/`TextArea` render their `label`
  prop into an invisible legend.** Import the wrapped versions from
  `web-ui/src/pages/GitConfig/fields.tsx`, not from `../../webui`.
- **`Radio` is a radio GROUP** (a `radios` array; a single `label`/`checked`
  blanks the page). `SelectInput` needs `values`, and its `onChange` hands back
  MUI's event — sweep every form in a browser before releasing.
- **Ignition rewrites the JSON it imports** (no final newline, escaped
  apostrophes, `"parent": ""`, reordered `files`), so git sees those files
  modified with identical content. Count changes through `IgnitionReformat`,
  never raw `Status`, or Pull refuses for ever.
- **Delivery is opt-in per project, only pulls in, and is only set from the
  Projects drawer** — never add a migration that writes one. Off refuses the
  runner; `POST /delivery` keeps runner mode and sync record exclusive. Polling,
  not a webhook: GitHub cannot reach most gateways. See `docs/AUTOMATION.md`.
- **Drawers render in a portal outside `.gitcfg`**: their body needs the `gitcfg` class.
- **A release replaces the project folder but carries `.git` and
  `global-props/data.bin` across**, or it destroys the repository and the
  gateway's project properties.
- **Runner routes read the raw query string**, never `getParameter` (Jetty 500s on a large body).
- **Styles:** colour from the platform tokens (`--neutral-*`, `--primary`,
  `--warning-dark` for amber text), the rest from `--gitcfg-*` in `_styles.scss`;
  no hex or font stack elsewhere. `.ia-card-header` and
  `.gitcfg-blank > div:first-child` reach into platform DOM.
- **Every project git operation goes through `GitManager.repoFor(project)`**,
  never `projects/<name>` directly: in Combined the project is a prefix of the
  data-dir repository. Paths handed to the Designer stay project-relative
  (`ProjectScope`, `Repo.inProject`/`toProjectPath`); writers hold
  `DataDirGitManager.lock()`. Combined commits use `--only`, never for an
  `rm --cached` (it re-adds from disk).
- **Modes (`docs/MODES.md`):** records go through `ModeStore` (`?saveTo=` any mode);
  credentials only via `GitManager.setAuthentication*` (policy, host checks);
  config remote ops use `authOrigin`, so `origin` follows the mode's record.
- **Designer RPCs act as the session, not their arguments**: `GatewayScriptModule.caller`
  and `project` read `RpcDelegate.CURRENT_CONTEXT` (user, open project); a route calls in
  with no RPC context and passes `req.getActor()`. Attaching a credential checks its owner.
- **Every client-supplied name or path goes through `SafePaths`** (project names,
  repo-relative paths, 5 MB reads); in Combined a leading `/` path is accepted only for a
  file in conflict. Remote URLs go through `PolicyGate.checkHost` (`RemoteUrls`).
- **Image snapshot walks the store tree** (`getImages` lists one level and
  returns folders as entries with no bytes) **and import merges, never
  deletes** — the store is gateway-scoped, so clearing it first would make
  one project's `images/` authoritative for every other project.

## Key libraries

- Eclipse JGit 7.8.1 — all git operations
- Apache MINA sshd 2.20.1 — SSH transport
- Lombok (designer)
- React 18 + RTK Query + `@inductiveautomation/ignition-web-ui` — the
  `web-ui/` gateway page, bundled via `com.github.node-gradle.node` + webpack

## Module packaging

Module ID `com.operametrix.ignition.git`; version from `version.properties`
plus a build timestamp. `requiredIgnitionVersion` is 8.3.0, apart from the SDK version. `:web-ui`'s bundle rides in the gateway jar via
`modlImplementation(project(":web-ui"))`.
Signing comes from `gradle.properties` (gitignored; template in
`gradle.template.properties`).
