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
that already works, with nothing reaching in. `RunnerSetup` generates the
setup (the `config.sh` registration command, the workflow YAML, and a
reachability check); `RunnerTrigger` is the route the runner calls,
authenticated by a bearer token rather than a gateway session, since a
workflow step has neither.

A runner-requested sync runs even when the project's scheduled sync is
disabled — turning the timer off means "pull on demand only", not "never
pull". It still needs a `GitSyncRecord` for that project, because the runner
route reads that record's branch and credential rather than carrying its own.

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

`POST /runner-sync` is mounted outside the normal session/CSRF model,
because a GitHub Actions workflow step has neither:

- HMAC/bearer token compared in constant time.
- Fails closed: no token configured, or the feature disabled, both 404.
- 64 KB body cap.

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
