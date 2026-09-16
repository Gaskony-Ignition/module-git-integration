# Automation — design note

Automation only pulls changes into this gateway; it never pushes. The
gateway-config repository pushes separately, by hand, from the Remote Sync
button on the Versioning page.

## The reachability problem

A GitHub webhook is GitHub making an inbound HTTPS connection **to** the
gateway. Most OT gateways sit behind NAT or a firewall and cannot be reached
that way — the same is true of customer sites generally, not just a
particular lab network. A webhook receiver is therefore a feature that most
installs could never use.

Inbound sync ships as two mechanisms instead:

**Polling (default).** A scheduled task per repository: `git fetch`, compare
the tracked remote branch to local, pull if it moved. No inbound exposure,
works behind NAT, works with no GitHub involvement at all. Interval
configurable, default 5 minutes.

**Actions runner (push-time).** A GitHub Actions self-hosted runner connects
*out* to GitHub and is handed workflow jobs over that same connection, so a
workflow step can call the gateway from inside the network — the direction
that already works, with nothing reaching in. The runner belongs on the host,
not inside a gateway container: on a Docker host it reaches every gateway on
its published port, so one runner serves them all. `RunnerSetup` generates the
setup (Linux, macOS and Windows registration commands, the workflow YAML, and a
reachability check). Each project chooses how the runner delivers to it:

- **Release.** On a version tag the workflow uploads a project export zip to
  `ReleaseReceiver` (`POST /runner-release`). The gateway replaces the whole
  project with it — a file dropped from the release disappears, which a
  copy-over never does — keeping the project's `.git` (the module's repository
  lives in the project folder) and `ignition/global-props/data.bin` (per-gateway
  settings such as the default database, which a release ships neutral). The
  upload is staged under `var/git-release`, outside `projects/`, then moved in
  and scanned: no restart, and no repository needed on the gateway. A release
  is authoritative and overwrites uncommitted edits, as any deployment does.
- **Repo updates.** On a push to the branch the workflow calls `RunnerTrigger`
  (`POST /runner-sync`) and the gateway pulls. With a `GitSyncRecord` the pull
  uses its branch and credential; without one it uses the project's checked-out
  branch, its remote, and the user whose stored credential that remote already
  uses. It runs even when the scheduled timer is off — "pull on demand only",
  not "never pull".

The difference matters because a repository and a release are different
things: the repository is raw source, often with tooling beside the project,
while a release is the packaged export that should replace the project whole.

The two routes hold a project to its chosen delivery: `/runner-release` refuses
a project set to Repo updates and `/runner-sync` refuses one set to Release,
both with 409 and the reason. Quietly performing the other one would let a
workflow aimed at the wrong route overwrite a project someone is pulling into.
`GitRunnerRecord.chosenMode` returns null for a project nobody has chosen for,
and both guards skip in that case, so a gateway upgraded from a version with no
modes keeps working until a choice is made.

### What the gateway actually acts on

Only two of the runner tab's values change what the gateway does: whether it
accepts runner requests, and the token. The gateway never reads its configured
address or its labels when a runner calls — those exist solely to fill in the
generated commands. They are therefore safe to preview before saving (the GET
takes them as query parameters and generates from them without writing), and
they sit beside the snippets rather than in the settings step. Presenting them
as gateway configuration is what made the tab read as five compulsory steps.

The same split decides what the gateway needs from git. In **Release** mode it
needs nothing: no repository, no remote, no credential, no route to GitHub — the
runner performs every git operation and the gateway receives an authenticated
zip. Only **Repo updates** and **Scheduled sync**, where the gateway itself
pulls, need the project registered with a remote and a credential.

### Runner scope and labels

A runner registers at exactly one scope — repository, organisation or
enterprise — and cannot move between them without re-registering, so
`RunnerSetup` generates both the repository and the organisation form
(`orgUrl` is the repository URL minus its last segment). Organisation is the
usual answer: a machine standing beside a gateway normally receives from
several project repositories, and a repository-scoped runner serves only one.
GitHub's own mechanism for grouping runners is runner groups, which private
repositories cannot use below the Team plan, so scope plus labels is the
portable arrangement.

Labels are the routing key. GitHub adds `self-hosted` plus the OS and
architecture automatically; the rest come from `--labels` at registration, and a
workflow's `runs-on` must list a subset of what the runner carries. A mismatch
queues the job for a day with **no error and no timeout**, which is the failure
mode here that nobody diagnoses unaided — hence the page generating both halves
from one field, and saying so. Two gateways must carry different labels, or a
job lands beside the wrong one; when a second appears, make the label a workflow
input rather than editing every workflow.

### Asking rather than telling

Two prerequisites are usually already in place, and both are detectable, so the
page reports on them rather than instructing:

- **A runner reaching this gateway.** `RunnerAuth` records when a runner last
  authenticated. That proves more than GitHub's runners API could tell us
  without an admin-scoped credential: a runner exists, reaches this gateway, and
  holds the right token. It is deliberately in memory — persisting it would put
  a config commit in the versioning history on every deploy — so a restart
  forgets it and the page words the empty case accordingly.
- **Workflows in the repository.** `WorkflowScan` lists
  `.github/workflows/*.y*ml` in the project's working tree and flags any whose
  text mentions a runner route. A repository that already deploys should gain
  one step, not a second workflow racing the first. A project with no working
  tree here reports "cannot be checked", which is not the same answer as "none".

The module does not install, register or supervise the runner. A runner
executes whatever the workflow file says, so hosting one from inside the
module would put repository-supplied shell next to the project store under
the gateway's own identity, and would need a stored GitHub admin credential
to keep re-fetching registration tokens.

**A webhook receiver was built and removed.** It authenticated correctly and
pulled the matching project, but could not receive a delivery on a gateway
GitHub cannot reach — which is most of them. Do not rebuild one without a
concrete gateway that GitHub can actually reach.

## The dirty-tree rule

Either mechanism can find local uncommitted changes — someone has a Designer
open. Both refuse rather than stash or force: log it, leave the tree alone.
Silently reverting an engineer's unsaved work is worse than not syncing.

## Workflow security model

`POST /runner-sync` and `POST /runner-release` are mounted outside the normal
session/CSRF model, because a GitHub Actions workflow step has neither:

- Bearer token compared in constant time, checked before the body is read.
- Fails closed: no token configured, or the feature disabled, both 404.
- `/runner-sync`: 64 KB body cap. `/runner-release`: 256 MB, project name
  limited to letters, digits, `_` and `-`, every zip entry must land inside the
  project folder, and `project.json` must be at the zip root.
- Query parameters are read from the raw query string: `getParameter` would make
  Jetty parse a large body without a zip Content-Type as a form and fail.
- A release and a sync of the same project never run at once.

## The workflow file is committed by the gateway

The module already holds push rights for the project repository, so *Commit
the workflow to the repository* writes `.github/workflows/ignition-sync.yml`
inside the project folder (the repository root for a project repo, and the
only place GitHub reads workflows from), commits it through the ordinary
project-commit path, and pushes. It refuses to overwrite a differing
workflow that is already there, and reports `unchanged` rather than making
an empty commit when nothing changed.

One GitHub requirement worth knowing: writing `.github/workflows/*` needs the
`workflow` scope on an OAuth-app token. A personal access token or deploy key
used by the gateway's own push does not hit this restriction.

## After the pull

Pulling changes files under `data/projects/<name>/`, which Ignition does not
notice on its own, so the pull is followed by a project scan request.
