# CLAUDE.md

Guidance for working in this repository. `docs/INTERNALS.md` has the REST
route table, the persisted resource types, the manager classes and the
Designer popup list — this file is standing instructions and gotchas only.

## Project overview

A Java module for Ignition 8.3 that embeds a Git client into the Designer:
commit, push, fetch, pull with merge-conflict resolution, revert, branch
management, snapshotting gateway-side resources (tags/themes/images) into the
project, and remote or local-only repository init — from the Designer's
dockable panels and status bar. It also versions the gateway **data
directory** (config-as-code) in a separate repo, surfaced through a gateway
web page (Platform → System → "Versioning"): config changes auto-commit as
they happen, and the page gives history/restore. Originally built by
AXONE-IO, maintained by Operametrix; this fork is Gaskony's build on top.

## Build commands

```bash
rm -rf build/moduleContent && ./gradlew build   # -> build/GitIntegration-<ver>.modl
```

Clear `build/moduleContent` first: after a version bump it keeps the previous
version's jars and the `.modl` ships both. A `clean build` can drop
`certificates.p7b`. Prettier violations fail webpack — run
`node_modules/.bin/prettier --write "src/**/*.{ts,tsx}"` in `web-ui/`.

No automated tests: install the `.modl` and exercise the Designer and the
Versioning page.

## Architecture

Gradle multi-module, following the Ignition Module SDK pattern:

```text
common    (scope: DG)   — RPC interface + abstract delegation base
designer  (scope: D)    — Designer UI: dockable panels, popups, status bar
gateway   (scope: G)    — all git operations + persistence + config-as-code REST routes
web-ui    (no scope)    — React gateway page, bundled into the gateway jar
```

The Vision client scope is unused — there is no `system.git.*` script module
on Vision clients.

## Gotchas

- **RPC implementation must implement `GitScriptInterface` directly.** 8.3's
  `RpcDelegate` discovers `@RpcInterface` only on the concrete class's direct
  interfaces, with no superclass walk.
- **A custom `ProtoRpcSerializer` registers a `Dataset`/`BasicDataset`
  Java-serialization adapter.** The default serializer has no `Dataset`
  support, so without it every `Dataset`-returning RPC round-trips empty with
  no error.
- **After any gateway-side project mutation (pull, checkout, init,
  snapshot), the Designer must call `GitBaseAction.pullProjectFromGateway()`.**
  It discards stale local edits via `DesignableProject.discardChanges`, then
  reflectively calls `IgnitionDesigner.updateProject()`; without it gateway
  changes never show.
- **The config-as-code repo is rooted at the data directory**, with
  `projects/` excluded from its `.gitignore` so the per-project repos are
  never nested inside it.
- **`ConfigAutoCommitter` (a `ResourceCollectionListener`) is the only live
  config-change notification** — per-resource listeners are never notified.
  Events within 2s coalesce into one commit; `commitLeftovers()` sweeps
  changes made while the gateway was offline.
- **Restore resets the working tree to the target commit and keeps HEAD on the
  branch** (unlike the detaching checkout), then applies it via a settings
  rescan — no restart.
- **`GITIGNORE_LINES` must ignore the SQLite sidecars `*-wal`/`*-shm`.**
  Without them `initRepo`'s `git add .` races the tag value store and dies
  with `FileNotFoundException`, so init never completes on a live gateway.
- **Excluded-files tree semantics**: excluding a tracked path also runs
  `git rm --cached`; re-including under a glob appends a negation rather than
  editing the glob (deleting `**/logs` to recover one file versions a
  gigabyte); nothing under an excluded directory can be re-included, so that
  row is read-only; a folder's tick is its children's roll-up, since a rule
  can exclude a directory's contents but not the directory.
- **Change badges are drawn by a `DotBorder`, not `addBadge`.** The badge list
  belongs to the tree's cell-renderer delegate, which stops painting added
  badges after the first commit of a session; a border sidesteps it entirely.
  Don't simplify this back.
- **`PerspectiveNavNode`/`VisionModuleNode` report no resource path**, so a
  change under them badges from the first resource-backed folder down, not
  the module root.
- **The platform's `TextInput`/`SelectInput`/`TextArea` render their `label`
  prop into an invisible legend.** Import the wrapped versions from
  `web-ui/src/pages/GitConfig/fields.tsx`, not from `../../webui`.
- **`Radio` is a radio GROUP** — it takes a `radios` array and maps over it.
  Passing `label`/`checked` as a single control reads `.map` on undefined and
  blanks the page. `SelectInput` needs `values` (not `options`), and its
  `onChange` hands back MUI's event, not the value — sweep every form in a
  browser before releasing.
- **Automation only pulls in; it never pushes.** Polling, not a webhook:
  GitHub cannot reach most gateways. Read `docs/AUTOMATION.md` before
  rebuilding an event bus or a webhook receiver.
- **A release replaces the project folder but carries `.git` and
  `global-props/data.bin` across**, or it destroys the repository and the
  gateway's project properties.
- **Runner routes read the raw query string**, never `getParameter`: Jetty
  would parse a large non-zip body as a form and 500.
- **The check command never carries an unchosen project's name**, and the
  gateway never reads its configured address at request time — it only fills
  in that command, so a GET may preview an unsaved one.
- **Styles: colour and weight from the platform tokens** (`--neutral-*`,
  `--primary`, `--success`, `--error`, `--warning-dark` for amber text);
  type, spacing, radius and the mono stack from the `--gitcfg-*` block at the
  top of `_styles.scss`. No hex or font stack anywhere else. `.ia-card-header`
  and `.gitcfg-blank > div:first-child` reach into platform DOM — re-check
  both after a web-ui package upgrade.
- **Image snapshot walks the store tree** (`getImages` lists one level and
  returns folders as entries with no bytes) **and import merges, never
  deletes** — the store is gateway-scoped, so clearing it first would make
  one project's `images/` authoritative for every other project.

## Key libraries

- Eclipse JGit 6.10.1 — all git operations
- Apache MINA sshd — SSH transport
- Lombok — annotation processing (designer)
- React 18 + RTK Query + `@inductiveautomation/ignition-web-ui` — the
  `web-ui/` gateway page, bundled via `com.github.node-gradle.node` + webpack

## Module packaging

Module ID `com.operametrix.ignition.git`; the version comes from
`version.properties` and the build appends a timestamp. `compileSdkVersion` is
decoupled from `requiredIgnitionVersion` so the `.modl` installs on any 8.3.x
gateway. `:web-ui`'s bundle rides in the gateway jar via
`modlImplementation(project(":web-ui"))`.
Signing comes from `gradle.properties` (gitignored; template in
`gradle.template.properties`). Every released `.modl` is signed.
