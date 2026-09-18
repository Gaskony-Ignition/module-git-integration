# Delivery — design note

Delivery only brings changes into this gateway; it never pushes. The
gateway-config repository pushes separately, by hand, from the Remote Sync
button on the Versioning page.

## One delivery per project, opt-in

Each project has exactly one delivery, set on the Projects tab: **Off**,
**Runner — release**, **Runner — repo updates**, **Sync — pull** or
**Sync — replace**. `POST /delivery` writes the two stores behind it together —
the runner's per-project mode map (`GitRunnerRecord`) and the project's
`GitSyncRecord` — so they can never both claim a project. A delivery that pulls
is refused for a project with no remote.

**Off is the default and refuses the runner.** Until 3.5.0 the runner switch and
token were the only gate: a workflow could install any project name it sent,
creating one if none existed, and the per-project choice only blocked the other
route. Now both runner routes refuse (409) a project that does not exist on the
gateway or is not set to that exact delivery. A new project's first release
therefore needs the project created empty first.

Upgrading to 3.5.0 runs `GitRunnerRecord.migrateToOptIn` once: with the runner
on, every project with no mode and no enabled sync is set to Runner — release,
so existing deploys keep working. The record's `optIn` flag then stops it
running again, so a project set to Off later stays Off.

## The reachability problem

A GitHub webhook is GitHub making an inbound HTTPS connection **to** the
gateway. Most OT gateways sit behind NAT or a firewall and cannot be reached
that way. So there are two mechanisms, both with nothing reaching in:

**Sync (pull).** A scheduled task per project: fetch, compare the tracked remote
branch with HEAD, act if it moved. Works behind NAT and with any git host.

- **Pull** fast-forwards, and refuses while the tree has real local changes or
  when the remote moved backwards.
- **Replace** hard-resets and cleans to the remote branch, keeping
  `ignition/global-props/data.bin`. It runs only when the branch moves, overwrites
  and counts local edits, and follows a branch moved backwards. This is the
  runnerless release route: a tag holds source, so a workflow builds each
  release and commits it to a branch of releases, and a promotion moves the
  gateway's own branch to the approved one.

**Runner (push).** A GitHub Actions self-hosted runner connects *out* to GitHub
and is handed jobs over that connection, so a workflow step can call the gateway
from inside the network. The module does not install or supervise the runner: a
runner executes whatever the workflow says, and hosting one inside the module
would put repository-supplied shell beside the project store.

- **Release.** The workflow uploads a project export zip to `ReleaseReceiver`
  (`POST /runner-release`). The gateway replaces the whole project — a file
  dropped from the release disappears — keeping `.git` and
  `ignition/global-props/data.bin`. Staged under `var/git-release`, moved in and
  scanned: no restart, no repository needed on the gateway.
- **Repo updates.** The workflow calls `RunnerTrigger` (`POST /runner-sync`) and
  the gateway pulls, using the project's sync record for branch and credential
  (kept, disabled, for exactly this) or its checked-out branch.

**A webhook receiver was built and removed.** It worked, but could not receive a
delivery on a gateway GitHub cannot reach — which is most of them.

## Local changes

Pull and repo updates refuse rather than stash or force: silently reverting an
engineer's unsaved work is worse than not syncing. Release and Replace are
chosen to be authoritative, so they overwrite — and Replace reports the count.

Ignition rewrites the JSON it imports (no final newline, escaped apostrophes,
`"parent": ""`, reordered `files`), so git sees those files modified with
identical content. `IgnitionReformat` counts changes by parsed content; without
it Pull refused for ever after the first import.

## Runner route security

`POST /runner-sync` and `POST /runner-release` are mounted outside the normal
session/CSRF model, because a workflow step has neither:

- Bearer token compared in constant time, checked before the body is read.
- Fails closed: no token, or the runner switched off, both 404.
- Opt-in per project, as above: 409 otherwise.
- `/runner-sync`: 64 KB body cap. `/runner-release`: 256 MB, project name
  limited to letters, digits, `_` and `-`, every zip entry must land inside the
  project folder, and `project.json` must be at the zip root.
- Query parameters are read from the raw query string: `getParameter` would make
  Jetty parse a large body without a zip Content-Type as a form and fail.
- A release and a sync of the same project never run at once.

## After the change

Changing files under `data/projects/<name>/` is not noticed by Ignition on its
own, so every delivery ends with a project import and a scan request, and an
event on the Logs tab.
