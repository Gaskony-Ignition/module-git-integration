# Git Integration

Version control for Ignition 8.3 — project resources from the Designer, gateway
configuration from the gateway. A Gaskony build of
[OperaMetrix's Git module](https://github.com/operametrix/ignition-git-module),
under the Beerware licence.

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

It also stops git being a thing you only do by hand. A branch can come back
down onto the gateway on a schedule, or the moment it moves on GitHub, without
anyone opening a Designer.

## What it looks like

Uncommitted work is marked in the Project Browser, so you find it without
opening the Commit panel. Green is new, amber is changed, and a folder carries
the state of what is inside it — red if something under it was deleted, since a
deleted resource has no tree node of its own to mark.

![Change badges in the Designer's Project Browser](docs/images/project-browser-badges.png)

The gateway's Versioning page decides what config-as-code covers. The tree is
rooted at `config/`, because that is the only thing the repository versions, and
a ticked row is a versioned one. Rows struck through are excluded, with the
`.gitignore` rule that excluded them shown on the right.

![The Excluded files tree on the gateway Versioning page](docs/images/excluded-files.png)

Which projects are under version control is answerable from the gateway, without
opening a Designer and without knowing where to look. Unversioned projects are
listed too — "not in git" and "not on this gateway" are otherwise
indistinguishable — and any of them can be initialised or given a remote here.

![The Projects tab listing every project and its git state](docs/images/versioning-projects.png)

Automation pulls changes into this gateway — it never pushes. Scheduled sync
fetches each project's remote on a timer and fast-forwards it when the tracked
branch moves; the Actions runner does the same the moment a branch moves on
GitHub, with nothing reaching in. Either way the event log below records
every commit, push, pull, config auto-commit and sync, successes and failures
alike — including an unattended sync that refused or failed, and why.

![The Automation tab, with its event log](docs/images/versioning-automation.png)

A merge does not have to wait for the next poll. A GitHub Actions self-hosted
runner connects out to GitHub from your own network and is handed workflow jobs
over that same connection, so it can ask the gateway to pull the moment a branch
moves — with nothing reaching in. The tab generates the whole setup with this
gateway's own values already in it: the registration command, the workflow, the
token, and a one-line check that the runner can reach the gateway.

![The Actions runner tab, with the generated setup](docs/images/versioning-runner.png)

## What it does

**In the Designer** — clone or initialise a project repository, manage remotes
and credentials, commit from a dockable panel with per-resource selection, amend
the last commit, browse history and diffs, and push or pull. Uncommitted
resources are badged in the Project Browser.

**On the gateway** — versions `config/` as code, commits automatically when a
config resource changes, shows history and per-commit diffs, restores a previous
commit, and pushes to a remote. The Excluded files tree edits `.gitignore`
directly: ticking a tracked path also untracks it, and rules you wrote by hand
are never rewritten. Project repositories, the credentials they authenticate
with, and the automation below are all managed from the same page.

**Automation** — pulls changes into this gateway; it never pushes. **Scheduled
sync** fetches each project's remote on a timer and fast-forwards it when the
tracked branch moves, then requests a project scan — polling rather than a
webhook, because GitHub cannot reach most gateways. A sync refuses when the
working tree is dirty rather than discarding someone's unsaved work. For
push-time sync instead of polling, the **Actions runner** tab generates the
setup for a GitHub Actions self-hosted runner and opens one token-authenticated
route for it to call; the module generates the configuration but never installs
or runs the runner itself. It warns when the selected project has no Scheduled
sync record — a runner pull needs that record's branch and credential — and the
install step covers both a Linux and a Windows runner machine. Either tab's
activity, plus every commit, push and pull from the Designer and every config
auto-commit, lands in the **Event log** below both.

This build adds the gateway-side Versioning page, change badges, and inbound
automation on top of upstream 2.1.0 — see [CHANGELOG.md](CHANGELOG.md) for the
full list.

## How to use it

Install the signed `.modl` from the latest release through the gateway's
**Config → Modules → Install or Upgrade Module**, then restart the gateway.

Project versioning: open a project, click **Configure** in the Designer's status
bar, and either clone a remote or initialise locally. Commit from the **Commit**
tab beside the Project Browser.

Gateway config versioning: **Platform → System → Versioning → Initialize
versioning**. Adjust what is covered under **Excluded files**.

Automation: **Platform → System → Versioning → Automation**. On **Scheduled
sync**, press *Set up* for a project, choose a branch and interval, then
*Sync now* — the result appears in the Event log below.

Push-time sync: **Automation → Actions runner**. Tick *Accept sync requests from
a runner*, enter the address the runner will reach this gateway on, and generate
a token — save it in the repository as the secret `IGNITION_SYNC_TOKEN`. Press
*Commit the workflow to the repository* and the gateway commits and pushes the
workflow itself. The one block left to copy is the runner install command; run
it on the runner machine, then run the test command before relying on it.

To build from source you need `gradle.properties` with the signing block —
copy it from `gradle.template.properties` and fill in the keystore details:

```bash
./gradlew build      # -> build/GitIntegration.modl, signed
```

The module version lives in `version.properties` and nowhere else.

## Licence

Beerware (Revision 42) — see [LICENSE.md](LICENSE.md) and `license.html`. The
original notice is retained; this build is modified and distributed by Gaskony.
