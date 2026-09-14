# Automation — design note

Status, 3.0.0 (14/09/2026): **built and current** — 3a Scheduled sync, 3c the Actions
runner, and the Event log that serves both. **Removed**: 3b the webhook receiver
(2.14.0) and features 1–2, Git events and Outbound triggers (3.0.0). What follows
was the original design note for all three features; sections 1 and 2 are kept as a
historical record — see "1 and 2: built in 2.12–2.13, removed in 3.0.0" below — and
3a/3b/3c describe what is actually running today.

Three features, in the order they were built. Each was independently useful;
each later one assumed the earlier ones existed.

1. **Git events** — a git operation raises an event the gateway can act on. *Removed 3.0.0.*
2. **Outbound triggers** — a successful push calls out to GitHub Actions, or anything else. *Removed 3.0.0.*
3. **Inbound sync** — a merge on the remote lands on the gateway. *Current.*

---

## What the existing code gives us for free

**Every git operation already runs in gateway scope.** The Designer holds no
JGit; `GitActionManager` makes RPC calls that land in `GatewayScriptModule`
(project repositories) or `DataDirGitManager` (the data-directory config
repository). So instrumenting "a commit happened" is two choke points, not a
search for call sites, and it captures Designer activity, Versioning-page
activity and scripted activity with the same code.

**Credentials already exist.** `GitUserHttpsCredentialRecord` is host +
username + secret, where the secret is either inline or a reference into a
Secret Provider. A GitHub PAT fits that shape unchanged.

**Config storage has a house pattern.** `GitConfigRemoteRecord` is the model to
copy: a `record Config(...)`, a `ResourceTypeMeta`, a `NamedResourceHandler`.
New settings follow it rather than inventing storage.

---

## 1 and 2: built in 2.12–2.13, removed in 3.0.0

Both were built, both worked, and both are gone as of 3.0.0 (14/09/2026).

**1. Git events** fired a flat dict — type, outcome, scope, project, user, branch, remote,
commit, message, files, timestamp — for every commit, push, pull and config auto-commit
(the design also listed fetch/checkout/branch/revert, but those were never actually wired
to fire one), delivered to a configured project-library script function and/or a Gateway
Event message handler, on a small bounded async queue so a slow handler could not stall a
git operation.

**2. Outbound triggers** posted a configurable JSON body to a URL on a matching event, with
GitHub `repository_dispatch`/`workflow_dispatch` presets, `${…}` substitution and a
credential-injected header.

**Why removed.** A review of the Automation page (14/09/2026) found neither did anything
nothing else already does. Commits and pushes are made in the Designer, which already
reports their outcome, so event delivery was a second channel for the same information.
Delivering a release to other gateways belongs to a deployment pipeline, not to the gateway
that was edited, and a GitHub remote already starts a workflow on push, so an outbound
trigger calling `repository_dispatch` reimplemented something the remote does itself.

**Do not rebuild either without a concrete gateway that needs a git-activity reaction nothing
else provides.**

---

## 3. Inbound sync

The goal: a merge to `main` on GitHub appears on the gateway, without anyone
opening a Designer.

### The reachability problem — read this before choosing

A GitHub webhook is GitHub making an inbound HTTPS connection **to your
gateway**. `ignition-module-testing` is on a VMware NAT network at
192.168.153.128; GitHub cannot reach it, and neither can it reach most customer
gateways, which is the normal condition for an OT network rather than an
accident. A webhook receiver would therefore be a feature that cannot be
demonstrated on the gateway it was built on, and cannot be used at most sites.

So inbound sync ships as **two mechanisms behind one setting**:

**3a. Polling (default, and what should be built first).**
A scheduled task per repository: `git fetch`, compare the tracked remote branch
to local, pull if it moved. No inbound exposure, works behind NAT, works with no
GitHub involvement at all — including against a local bare repo or a self-hosted
GitLab. Interval configurable, default 5 minutes. The cost is latency and a
fetch every interval, both of which are nothing.

**3b. Webhook (optimisation, for gateways that are actually reachable).**
Same pull logic, triggered by a POST instead of a timer. Worth building only
once polling works, because it is the same action with a harder front door.

### Webhook security model

Every existing route is `requirePermission(READ|WRITE)` and every mutation
requires an `X-CSRF-Token` from a logged-in web session. **GitHub has neither.**
The webhook route must therefore be mounted outside that model, which is the
whole security design:

- **HMAC.** GitHub signs the body with a shared secret and sends
  `X-Hub-Signature-256`. Verify HMAC-SHA256 over the **raw bytes**, with a
  constant-time compare. The raw body must be captured before any JSON parse —
  a re-serialised body will not match.
- **Fail closed.** No secret configured ⇒ the route 404s. Not enabled ⇒ 404.
  Never a default secret, never an "insecure mode" flag.
- **Narrow accept.** Only `X-GitHub-Event: push`, only when `ref` matches the
  repository's configured branch. Everything else is a 204 and a log line.
- **Replay.** Keep a bounded set of recent `X-GitHub-Delivery` ids and drop
  repeats.
- **Single-flight.** One pull per repository at a time; a burst of pushes
  coalesces into one pull, and the route returns 202 immediately rather than
  holding GitHub's connection open for the duration of a clone.
- **Optional source allowlist**, from GitHub's published hook ranges.

### The dirty-tree question

Either mechanism can find local uncommitted changes — someone has a Designer
open. **Refuse, do not stash and do not force.** Log it, fire a `pull/failure`
git event so feature 1 can raise an alarm, and leave the tree alone. Silently
reverting an engineer's unsaved work is worse than not syncing.

### After the pull

Pulling changes files under `data/projects/<name>/`, which Ignition does not
notice on its own. The pull is followed by a project scan request so the gateway
picks the resources up. Whether a Designer with the project open reloads
cleanly needs testing — that is the main unknown in this feature and the reason
it is third.

---

## Build order and rough size — historical

This was the pre-build plan. All four rows happened (2.12–2.16), in this order, and 1/2/3b have
since been removed again (3b in 2.14.0, 1 and 2 in 3.0.0) — kept here only to show the reasoning
did not skip a step.

| | Feature | Depends on | Size |
|---|---|---|---|
| 1 | Git events + Automation tab | — | small |
| 2 | Outbound triggers + GitHub presets | 1 | small |
| 3a | Poll-and-pull sync | 1 | medium |
| 3b | Webhook receiver | 3a | medium, security-sensitive |

1 and 2 together are one release. 3a is the next. 3b only if a gateway that
GitHub can reach is actually in scope.

## Open questions for sign-off — historical, answered

- **Handler shape.** Moot: event delivery (feature 1) is removed as of 3.0.0.
- **Which gateway runs a scheduled sync in a redundant pair?** Still open. `SyncScheduler` has no
  redundancy check, so it runs wherever the module starts; behaviour on a redundant pair has not
  been tested.
- **Config repository as well as projects?** Still no. The data-directory repository pushes
  manually only; nothing polls or pulls it automatically.

---

## 3b: built in 2.13.0, removed in 2.14.0

It was built and then taken out again, so the reasoning is worth keeping.

The receiver worked. It dispatched on `X-GitHub-Event`, authenticated each
delivery by HMAC over the raw bytes, rejected replays, and pulled the matching
project — proved on the test gateway with signed synthetic deliveries.

What it could never do here is receive a delivery from GitHub. A webhook is
GitHub opening a connection **to** the gateway, and a credential cannot create
an inbound route: an HTTPS token or an SSH key authenticates the gateway calling
**out**, which is the direction that already works. Proving the feature would
have meant putting a gateway on the public internet behind a tunnel, and keeping
it working would mean every site doing the same.

So the module carries a second inbound mechanism nobody could use, with the
security surface of an unauthenticated route, to save a poll interval. Scheduled
sync reaches the same state over the connection every gateway already has.

Do not rebuild this without a concrete gateway that GitHub can reach.

---

## 3c: the runner, built in 2.16.0

The webhook's problem was direction, not intent. Push-time sync is still worth
having; it just cannot be GitHub dialling in.

A GitHub Actions **self-hosted runner** inverts the connection. It is a service
on your own network that long-polls GitHub over outbound HTTPS and is handed
workflow jobs on that same connection. Nothing has to reach the gateway from
outside, no public address exists, and no HMAC secret is shared with a third
party. The workflow step then calls the gateway across the local network.

So the module does two things:

- **Generates the setup.** The `config.sh` registration command carrying the
  project's own repository URL and the runner labels, the workflow YAML
  targeting those same labels, and the `curl` that proves reachability before a
  workflow depends on it. These four values — repository, labels, gateway
  address, token — have to agree across two systems and three files, and one of
  them being subtly wrong produces a workflow that queues forever with no error.
  That failure is the whole reason the tab exists.
- **Opens one route for the runner to call.** `POST /runner-sync`, bearer token
  compared in constant time, 64 KB body cap, 404 until a token is generated. The
  token is generated by the gateway, stored encrypted, and returned exactly
  once.

### What it deliberately does not do

The module does not install, register or supervise the runner. A runner executes
whatever the workflow file says. Hosting one from inside the module would put
repository-supplied shell next to the project store under the gateway's own
identity, would need a stored GitHub admin credential to keep re-fetching hourly
registration tokens, and would vanish on a container recreate. Generating the
commands costs nothing and leaves the trust boundary where it belongs.

### Why this is not the webhook again

The route is unauthenticated by the platform's own session machinery, exactly as
the webhook's was, and that is the fair objection. The difference is who can
reach it. A webhook route is only useful if the internet can address it; this
one is only ever called from inside the same network, so it can sit behind
whatever already protects the gateway. Turning the feature off closes it
entirely — with no token, it is a 404.


### The workflow file is committed by the gateway (2.17.0)

The module holds push rights for the project repository already. Generating a
file for someone to copy into that same repository by hand was a step with no
purpose, so *Commit the workflow to the repository* does it: write, commit
through the ordinary project-commit path, push.

The file goes to `.github/workflows/ignition-sync.yml` **inside the project
folder**, because for a project repository the project folder is the repository
root, and GitHub reads workflows only from the root. The leading dot keeps it
out of Ignition's resource scan — verified on 8.3.8, no scan error and the
project stays healthy.

Two refusals worth keeping: it will not overwrite a workflow that is already
there and differs (it may have gained steps that are nothing to do with this
module), and re-running it with nothing changed reports `unchanged` rather than
making an empty commit. A commit that cannot be pushed reports exactly that; the
commit stands.

### Proved end to end (2.18.0)

Everything above was verified against the module alone until 10/09/2026, when the whole loop was
run for real: a GitHub repository, a self-hosted runner registered with the generated `config.sh`
command verbatim, the gateway's own committed workflow, and a push to the default branch.

A push at 03:31:00Z reached the gateway at 03:31:11Z. The workflow run reported
`{"ok":true,"project":"_runner_live_","result":"pulled 2 file(s)"}`, the files were on disk, the
project came back clean and the sync event logged `Pulled 2 changed file(s) from origin/master`.

Two defects only that exercise could find, both fixed:

- The generated workflow triggered on `main` while project-init creates `master`. A trigger naming
  a branch that does not exist produces no run and no error.
- A credential could not be attached to a project remote from the gateway page at all, so a remote
  set up there could never authenticate.

One thing worth knowing before setting this up: GitHub refuses to let an OAuth-app token write
`.github/workflows/*` without the `workflow` scope. A personal access token used to push the
workflow by hand needs that scope; the gateway pushing it with the project's own credential does
not hit this, because a PAT or deploy key is not an OAuth-app token.
