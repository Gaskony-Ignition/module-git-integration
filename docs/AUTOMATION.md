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

**Nothing but the drawer ever sets a delivery.** 3.5.0 tried to spare gateways a
re-pick by giving Runner — release to every project the runner could already
have delivered to. It read the data directory, so unversioned projects were
included, and the result was a table where every row claimed a delivery nobody
had chosen — the opposite of opt-in. A stored delivery cannot be told apart from
a chosen one, so 3.7.0 clears them once
(`GitRunnerRecord.clearAssignedDeliveries`, plus disabling every sync record) on
any gateway holding a runner resource, which are the only ones that could have
been assigned. The record's `cleared` flag stops it happening twice, and
`chosenMode` now reads by value, so a key left behind by an older version is not
a delivery either.

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

## The config repository follows a branch too

Configure Versioning has the same choice for the gateway config repo: Off, or **Sync — replace**
with a branch and an interval. It fetches on the same timer, and when the branch tip is not the
one last applied (or HEAD's tree no longer matches it) it points the local branch and HEAD at the
tip, hard-resets, removes untracked files the repository does not ignore, and applies with a
settings rescan. Local commits made by the auto-committer are discarded and counted in the Logs
entry, so a gateway that follows a branch is meant to change only through that branch. The
auto-committer is held while it runs and for a few seconds after, so the rescan's own events
commit nothing. Untracked files go one by one, never `clean -d`, so gitignored data inside an
untracked directory survives. The setting lives in `.git/config`, not a resource: a resource would
be versioned, and the branch's copy would overwrite it.

## Combined layout: one delivery for the gateway

In the Combined repository layout (see README) config and every project are one repository, so a
pull cannot be per project. The gateway delivery in Configure Versioning then offers **Sync — pull**
(merge the branch in on a timer, refused while anything on the gateway is uncommitted, a branch
moved backwards not followed), **Sync — replace** as before but for the whole gateway, and **Runner —
repo updates** (`POST /runner-sync` pulls the gateway branch whatever project the body names; 409
unless this delivery is chosen). Each ends with a config scan and an import of every project the
change touched. A project keeps **Off** or **Runner — release**: a release still replaces one
project folder, and is then committed for that folder only.

Per-project sync records and runner repo-update modes are kept, inactive, while Combined, and the
drawer never rewrites them unless that project's delivery is changed — so switching back to
Separate brings them back. The gateway's Pull and Runner modes are likewise kept but inactive while
Separate.

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

## Runner setup

For a GitHub remote, use a fine-grained token per gateway: owned by the
organisation, limited to the repositories that gateway deploys, **Contents:
Read-only** unless the gateway pushes. The Credentials tab then shows it reading,
not pushing. Expiry is read for github.com only; SSH keys and other hosts show
reach alone.

Once per gateway:

1. **Credentials → Runner access → Edit**: tick *Accept deliveries from a
   runner* and *Generate a token*, Save. Copy the token — it is shown once, and
   a new one stops the old one working. Save it as an Actions secret in every
   repository that deploys here.
2. A GitHub self-hosted runner on a machine that can reach the gateway, with a
   label no other runner carries.
3. A workflow whose `runs-on` names that label and which sends the token to the
   gateway, at the address the runner reaches it on (for a runner on the same
   Docker host, `http://localhost:` and the published port).

Check the runner can reach the gateway before relying on it. With no zip, a
correct address and token answer 400 "no release zip"; 401 is a wrong token,
404 the runner switched off, 409 a project not set to Runner — release:

```bash
curl -sS -X POST -H 'Authorization: Bearer <token>' \
  "http://<gateway>/data/git-config/runner-release?project=<project>"
```

```powershell
try { Invoke-RestMethod -Method Post -Headers @{ Authorization = "Bearer <token>" } `
  -Uri "http://<gateway>/data/git-config/runner-release?project=<project>" }
catch { $_.Exception.Response.StatusCode.value__ }
```

A repo update is `POST /data/git-config/runner-sync` with the body
`{"project": "<name>"}`.

**Push or pull?** A runner (push) can build, gate and report success back to
GitHub, and a release needs no git credentials on the gateway. Sync (pull) needs
no machine beside the gateway, but the gateway needs a credential and a route to
the git host, and whoever moved the branch learns nothing about whether it was
applied — check Logs.
