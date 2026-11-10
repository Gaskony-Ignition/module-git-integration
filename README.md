# Git Integration

Version control for Ignition 8.3 — project resources from the Designer, gateway
configuration from the gateway. A Gaskony build of
[OperaMetrix's Git module](https://github.com/operametrix/ignition-git-module),
under the Beerware licence.

> **Not an Inductive Automation product, and not supported by Inductive Automation.** Independent work, largely built with AI tools and tested for one purpose on a limited subset of gateway versions and platforms. Take the ideas; fork and review it before it goes near production. Feedback is welcome in Issues; improvements are made where possible, but no support is guaranteed. [NOTICE.md](NOTICE.md) says more.

## Why this exists

Two people editing the same gateway is two people overwriting each other, and a
gateway backup is a snapshot, not a history: it cannot tell you what changed
between Tuesday and Friday or who changed it. Ignition has no built-in answer,
so the answer is the one every other trade already uses — put it in git.

Upstream covers the Designer half well. This build adds what was missing in
practice: seeing at a glance which resources you have not committed, controlling
what the gateway-config repository actually versions (so a repo never silently
fills with SQLite databases and log files), and answering from the gateway
itself which projects are in git at all.

It also stops git being a thing you only do by hand. A release or a branch can
come down onto the gateway on a schedule, or the moment it is published, without
anyone opening a Designer.

## What it looks like

Uncommitted work is marked in the Project Browser, so you find it without
opening the Commit panel. Green is new, amber is changed, and a folder carries
the state of what is inside it — red if something under it was deleted, since a
deleted resource has no tree node of its own to mark.

![Change badges in the Designer's Project Browser](docs/images/project-browser-badges.png)

The gateway's Versioning page decides what config-as-code covers, and carries
the module's version under its title. The **Git Ignore** tree shows the whole
data directory, because that is what the repository sits in: a ticked row is
versioned, an unticked one is greyed and says why on the right — its own
`.gitignore` line, an inherited rule, or its parent folder. `.gitignore` is the
only thing that decides, so anything listed can be ticked on. By default only
`config/` is versioned: the rest of the data directory holds databases, logs,
the per-project folders (each has its own repository), other modules' runtimes,
and files specific to one machine (`ignition.conf`, `modules.json`). A data
directory is deep, so the tree has a search that finds a file or folder anywhere
in it, a jump to any top-level folder, a filter that hides everything excluded,
and Expand/Collapse.

![The Git Ignore tree on the gateway Versioning page](docs/images/excluded-files.png)

The **Projects** tab lists every project on the gateway — unversioned ones too,
since "not in git" and "not on this gateway" otherwise look the same — and how
changes reach each one. **Edit** opens a drawer with the project's repository
and its delivery.

![The Projects tab: each project's repository, delivery and state](docs/images/versioning-projects.png)

![A project's drawer: repository, then delivery](docs/images/versioning-project-drawer.png)

**Credentials** holds what the gateway authenticates with: to repositories, and
from a GitHub Actions runner. Each repository credential shows when its token
expires, what it is allowed to touch, and which projects it can read or push —
asked of the host on save, daily and on **Check**, since a token carries none of
it. **Scope** answers a different question from **Reaches**: a classic token
reaches every repository its account can, and is flagged for it. GitHub will not
report a fine-grained token's grants, so that row says so rather than guessing —
**Reaches** is measured, not claimed. A project whose
credential has expired, is refused, or cannot read its remote says so on the
Projects tab, as does one expiring within 14 days. **Edit** replaces a token in
place, so the projects using it stay linked.

![The Credentials tab: expiry and reach per credential](docs/images/versioning-credentials.png)

**Logs** lists every commit, push, pull, sync and
release since the gateway started, including an unattended one that failed, and
why.

![The Logs tab](docs/images/versioning-logs.png)

## What it does

**In the Designer** — clone or initialise a project repository, manage remotes
and credentials, commit from a dockable panel with per-resource selection, amend
the last commit, browse history and diffs, and push or pull. Uncommitted
resources are badged in the Project Browser.

**On the gateway** — versions the data directory as code, commits automatically
when a config resource changes, shows history and per-commit diffs, restores a
previous commit, and pushes to a remote. **Git Ignore** edits `.gitignore`
directly: unticking a tracked path also untracks it, and rules you wrote by hand
are never rewritten.

**Delivery** — each project has one, chosen on the Projects tab. It only ever
brings changes in; nothing is pushed.

| Delivery | What happens |
| --- | --- |
| Off (default) | Nothing. A runner delivery for it is refused. |
| Runner — release | A GitHub workflow uploads a release zip; the project is replaced by it, keeping its git repository and project properties, with no restart. |
| Runner — repo updates | A workflow asks the gateway to pull the branch. |
| Sync — pull | The gateway fetches on a timer and fast-forwards; refused while anyone has local changes. |
| Sync — replace | The gateway fetches on a timer and makes the project match the branch exactly, rollbacks included — pointed at a branch of built releases, it installs releases with no runner. |

Runner deliveries need **Credentials → Runner access** switched on and a token.
Repo updates and sync need a remote and a credential; until there is a
remote they are disabled.

This build adds the gateway-side Versioning page, change badges, and delivery on
top of upstream 2.1.0 ([CHANGELOG.md](CHANGELOG.md)).

The Versioning page meets WCAG 2.1 AA, short of three platform controls with no
naming prop of their own: the gateway's own header logo, the History table's
page-size selector and row-expand button, and the raw `.gitignore` editor's
resize handle.

## Repository layout

Some teams want each project in its own repository; others want the whole
gateway — config and every project — as one repository they can read, review
and branch as a unit. A gateway setting picks one, and an admin can switch
either way at any time.

| | Separate (default) | Combined |
| --- | --- | --- |
| Repositories | One per project at `projects/<name>`, plus the config repository at the data directory | One at the data directory, holding `config/` and `projects/` |
| Designer commit, diff, history, revert, badges | That project's repository | Limited to `projects/<name>/`; a commit never takes config or another project with it |
| Branches, checkout, pull, push, fetch | Per project | Gateway-wide: checking out a branch switches config and every project, then reloads them |
| Remote and credential | Per project, in the Designer's Remotes popup | One, set in Configure Versioning; the Designer shows it read-only |
| Delivery | Per project on the Projects tab | Pulls set once in Configure Versioning › Delivery (Sync — pull, Sync — replace, Runner — repo updates); a project keeps Off or Runner — release |

**Branches.** One gateway has one checked-out branch per project (Combined: one for
everything), shared by everyone editing it, so the gateway page shows it. The
Projects tab has a Branch column with ↑/↓ against the upstream, and each
project's drawer has a **Branches** tab; the Versioning page has a **Branches**
tab for the gateway config. Each draws the commit graph beside the branch list,
with gone, merged, ahead and behind marked. In Combined the project view shows
the gateway repository and dims commits that leave that project. **Switch to
this branch** changes it from the page; it is refused, with the files, while
there is uncommitted work or while a sync or delivery follows another branch.

![A project's Branches tab: commit graph and branch list](docs/images/versioning-branches.png)

In Combined, a revert from the Designer is refused when the commit also changed
files outside that project, rather than undoing config or another project's
work from a window that cannot show it. Config's auto-commit and **Restore**
leave `projects/` alone. A runner release and a deleted project are each
committed for that project's folder only. An open Designer follows a layout or
branch switch within ten seconds, without reopening.

![The Repository layout setting](docs/images/versioning-layout.png)

To switch: **Versioning → Configure Versioning → Repository layout**. It is
refused, with nothing changed, while any project has uncommitted work.

- **Separate → Combined** moves each project's `.git` to
  `var/git-archive/<name>-<time>.git` (kept, so its history and remotes can come
  back), adds `projects/` to the gateway repository's `.gitignore` rules with
  each project's `global-props/data.bin` left out, and commits every project in
  one commit. History is not merged in.
- **Combined → Separate** gives each project its own repository again — its
  archived one if you tick *Restore each project's earlier history* and one
  exists, with one commit for what changed meanwhile; otherwise a new repository
  with one commit of its current files — and takes `projects/` back out of the
  gateway repository.

Per-project remotes, credentials and deliveries are kept while Combined and
work again after switching back; the gateway's Pull and Runner deliveries stop
while Separate.

## How to use it

Install the signed `.modl` from the latest release through the gateway's
**Config → Modules → Install or Upgrade Module**, then restart the gateway.

Project versioning: open a project, click **Configure** in the Designer's status
bar, and either clone a remote or initialise locally. Commit from the **Commit**
tab beside the Project Browser.

Branches: the **Branch** button opens local and remote lists. A fetch drops remote
branches that were deleted on the remote, and the local list marks a branch
`[gone]` (its remote branch was deleted), `[merged]` (already in the current
branch's upstream, else HEAD) or `↑3` (three commits on no remote). Delete refuses
the checked-out branch and the branch a scheduled sync follows. A branch with
unmerged or unpushed commits asks again, with the commit count, before **Delete
anyway**. **Clean up…** fetches, then offers every gone or merged branch, ticked.

![Branch list with gone and merged marks, and Clean up](docs/images/designer-branch-cleanup.png)

Gateway config versioning: **Platform → System → Versioning → Initialize
versioning**. Adjust what is covered under **Git Ignore**.

A project's delivery: **Versioning → Projects → Edit**, choose the delivery,
Save. A project must exist on the gateway first — for its first release, create
it empty under **Platform → Projects**.

For a GitHub remote, use a fine-grained token per gateway: owned by the
organisation, limited to the repositories that gateway deploys, **Contents:
Read-only** unless the gateway pushes. The Credentials tab then shows it reading,
not pushing. Expiry is read for github.com only; SSH keys and other hosts show
reach alone.

Runner delivery, once per gateway:

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

To build from source you need `gradle.properties` with the signing block —
copy it from `gradle.template.properties` and fill in the keystore details:

```bash
./gradlew build      # -> build/GitIntegration.modl, signed
```

The module version lives in `version.properties` and nowhere else.

## Licence

Beerware (Revision 42) — see [LICENSE.md](LICENSE.md) and `license.html`. The
original notice is retained; this build is modified and distributed by Gaskony.
