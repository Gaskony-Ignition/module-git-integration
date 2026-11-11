# Deployment modes

Ignition 8.3 lets one gateway configuration carry several deployment modes —
Dev, Test, Prod — where a mode overrides some resources and inherits the rest
from `core`. Git Integration lets each mode decide what the module may do
there, and with which remote and credential. A typical split: Dev pushes to
GitHub, Prod only pulls, with a read-only token of its own.

This works the same in both repository layouts ([LAYOUT.md](LAYOUT.md)).

## Setting the mode

The active mode is set per gateway, not in the Designer or on the Versioning
page. Create the modes under **Platform › Modes**, then in `data/ignition.conf`
add a line such as

```text
wrapper.java.additional.20=-Dignition.config.mode=Prod
```

(any unused index), and restart the gateway. The Versioning page shows
**Deployment mode: Prod** under its title, or `core` when no mode is set.

## The Git policy

**Versioning › Policy** lists what the module may do on this gateway and, once at the top,
where the settings come from ("Settings from Prod"). A value is red only when it is the less safe
choice (Insecure remotes: On).

| Setting | Off means |
| --- | --- |
| Push to remotes | The page's Remote sync, the Designer's Push and a project's first push from Initialise are refused. |
| Force push | The Designer no longer offers to force push after a rejected push. |
| Commit from the Designer | Commit, amend, revert and completing a merge are refused in the Designer. The gateway's own config auto-commits carry on. |
| Create, switch and delete branches | The Designer's branch actions and the page's Switch to this branch are refused. |
| Switch the repository layout | The layout switch in Configure versioning is refused. |
| Deliveries | Only the listed kinds (Sync — pull, Sync — replace, Runner) can be chosen, and the timer and the runner refuse the others. Off is always allowed. Add from repository needs Sync — pull or Sync — replace in the list. |
| Allowed remote hosts | Blank allows any. Otherwise every remote URL is checked before the gateway contacts it, and when a remote is saved or tested. `*.example.com` matches any subdomain. |
| Insecure remotes | Off by default (4.3.0). Off, a remote must be `https://` or `ssh://` (or `git@host:path`) on a host that is not this machine or its link-local network. On allows local folders, `file://`, `git://`, `http://`, `localhost` and `169.254.x.x` — for a lab, not production. |
| Only deliver commits merged on GitHub | Off by default (4.4.0). On, a delivery installs a branch tip only when GitHub signed it, from a GitHub host — see below. |
| GitHub hosts | `github.com` unless set: the hosts the option above trusts (add a GitHub Enterprise host). |

The default allows everything earlier versions allowed, any host, except
insecure remotes. A URL with a password in it (`https://user:pass@host`) is
refused whatever the policy; save the password as a credential.

![The Policy tab in deployment mode Prod: each setting and its value, and where the settings come from](images/versioning-policy.png)

A refused action stays on screen, disabled, with the reason: on the page as
text, in the Designer in the menu or button ("Checkout (off in Prod)"). The
gateway refuses it either way — a route answers 403 and the Designer shows the
message, for example *Branch changes are off in Prod (Git policy).* A host
outside the list reads *gitlab.com is not an allowed remote host in Prod (Git
policy).*

![The layout switch disabled, with the reason, in deployment mode Prod](images/versioning-policy-refused.png)

## Per-mode remotes and credentials

The drawers for the policy, the config remote, a credential, a project's delivery and runner
access work like the platform's own resource pages. The kebab (⋮) in the drawer's header has,
under **RESOURCE DEFINITIONS**, **Create Override** — pick any mode created on this gateway, not
only the active one — and **Remove Override** for the definition being edited. Once a record has
more than one definition, **Select Definition to Configure** picks which one the drawer shows and
Save writes; the one this gateway runs on is marked "(active)". Lists say which modes override each
record ("Overridden in Prod"). A record that exists only in a mode (a credential added there) is
listed too, marked "Only in Prod"; a new record's drawer asks which collection to create it in.

![The policy drawer on Prod's definition, with the kebab's Create Override and Remove Override](images/versioning-save-to.png)

The same records can also be overridden from **Platform › Modes** or the
gateway's `resources/copy` API; the module reads whichever value the active
mode resolves to.

For a different secret per mode, prefer a **Secret Provider reference** over a
typed-in secret. A typed-in secret is encrypted with this gateway's key; an
override with one decrypted on the same gateway (8.3.10), but whether it
decrypts on another gateway that follows the branch has not been tested — and
these records are not versioned anyway (below).

## What travels in git

Only the Git policy. The data-directory repository's `.gitignore` keeps every
other record of this module out:

```text
**/com.operametrix.ignition.git/*
!**/com.operametrix.ignition.git/git-policy/
```

So a Prod override of the policy written on a Dev gateway reaches Prod in the
branch it follows (Sync — replace or pull), while remotes, credentials and
deliveries stay on the gateway they were set on. An older repository
has its one `**/com.operametrix.ignition.git/` line rewritten into these two
lines once, at startup, and committed; nothing else in the file is touched, and
a later edit of those lines is kept (marker `.git-module-policy-versioned` in
the data directory).

## Setting up Dev and Prod

1. On every gateway, create the modes and set the active one in
   `ignition.conf` (above).
2. On Dev (mode `Dev`): open **Policy**, leave core as it is. Configure the
   remote with a token that can push, **Save to: core**.
3. Still on Dev, open **Policy › Edit policy**, ⋮ › **Create Override** › Prod, and set Prod's
   values: Push off, Commit from the Designer off, branches and layout off, deliveries Sync —
   replace only, allowed hosts `github.com`. Save. The config repository commits it; push, and the
   override is in the branch. (The gateway's `resources/copy` API does the same, for scripting.)
4. On Prod (mode `Prod`): Sync — replace from that branch brings the policy in.
   Configure the remote with a read-only token, **Save to: Prod**, ideally as a
   Secret Provider reference.

## Remotes and credentials go to one host only

The remote record resolves per mode, but `data/.git/config` does not, so
`[remote "origin"]` is set from the effective record before every push, fetch
and pull of the gateway repository, and any push URL on it is removed. A
credential is refused for a host other than its own:

- the config remote's credential only goes to the host of its URL — including,
  in Combined, a Designer push to another remote;
- an HTTPS credential only goes to the host in its host field (`github.com`,
  `*.example.com`). Since 4.3.0 the field is required to be a host; a credential
  from an earlier version whose field was a label was bound at start-up to the
  one host its remotes use, and otherwise is refused until its host is set (the
  gateway log names it);
- changing a project remote's URL to another host detaches its credential;
- testing a saved credential against a URL on another host asks for the secret
  again, and a Secret Provider reference can only be tested when a saved
  credential for that host already uses it.

A policy that is saved but cannot be read is treated as locked (no push, branch or layout
switch, no delivery but off, no remote), never as the permissive default; the gateway log says so.

The gateway does not follow HTTP redirects (`http.followRedirects=false` in every repository),
so a credential never travels to another host; a moved server needs the remote URL updated.

TLS certificates are always verified (versions before 4.3.0 turned that off in
every repository they created; start-up turns it back on). A git server with a
certificate from a private CA needs that CA in the gateway's Java truststore —
see [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## Production checklist

- The git server's CA in the gateway's truststore, if it is private.
- In Prod, the policy overridden read-only: push, Designer commit, branches and
  layout off; only the deliveries Prod uses; insecure remotes off.
- **Allowed remote hosts** set to your git host.
- Credentials as Secret Provider references, read-only tokens in Prod.
- Branch protection on every branch a gateway follows ([AUTOMATION.md](AUTOMATION.md),
  "Who can push is who can deploy"), the config branch above all: the policy travels in it, so
  whoever can push there can loosen Prod's policy. When a delivery, restore, override or mode
  switch makes the effective policy allow more, the gateway log and the Logs tab get a warning
  naming each loosened setting: *Policy now allows more in Prod: push to remotes, branches.*
- Optionally, **Only deliver commits merged on GitHub** in Prod (below).
- An audit profile for the module's essential actions (below).
- Internal user sources keep password hashes in `users.json`; the gateway repository never
  versions it (4.4.0). A repository from before 4.4.0 still holds it in older commits: if that
  repository was shared, rotate those passwords.
- The Versioning page needs WRITE permission: give it to gateway administrators
  only.

## Only deliver commits merged on GitHub

For a security-conscious site, optional and off by default. On, every delivery — Sync — pull and
Sync — replace for a project or the gateway repository, and Runner — repo updates — installs a
branch tip only when its commit carries a valid signature by GitHub's web-flow key, and **Add from
repository** adds a project only on such a commit. GitHub signs the commits it makes itself: a pull
request merged with its merge or squash button, and an edit made in its web editor. A commit pushed
from someone's machine is not signed by that key, so the gateway stays on what it has and the Logs
tab gets one warning per refused tip: *Not merged through GitHub: 1a2b3c4 — the commit is not
signed.* The commit checked is the commit installed: there is no second fetch in between.

The signature says GitHub made the commit, not which repository or branch it was made on. So the
option only means "merged here" when:

- the remote is GitHub itself — `github.com`, or a GitHub Enterprise host listed under **GitHub
  hosts** in the policy; any other remote is refused (*the remote isn't GitHub, so 'merged on
  GitHub' can't be checked*);
- the branch the gateway follows is protected on GitHub: pull requests required (and, where it
  matters, no edits in the web editor), and **no force-push**. Sync — replace follows a branch that
  was force-pushed back to an older GitHub-signed commit — that is a rollback by design — so only
  branch protection stops someone from rolling a gateway back that way.

Runner — release installs a zip, not a commit, so the option does not apply to it. Manual actions
on the page — Pull, Update from remote, restore, switch branch, Join remote history — are not
deliveries and are not checked; Add from repository is checked.

The check is a v4 OpenPGP RSA signature over SHA-256/384/512, issuer and time from the signed part
only, against the keys bundled in the module — nothing is fetched at run time. The bundle holds
the keys published at `https://github.com/web-flow.gpg`: `968479A1AFF927E37D1A566BB5690EEEBB952194`
(valid from 16/01/2024) and `5DE3E0509C47EA3CF04A42D34AEE18F83AFDEB23` (valid until 16/01/2024),
trusted only for signatures whose signed creation time falls before that date. When GitHub publishes a new
key, a module release carries it
(`gateway/src/main/resources/com/operametrix/ignition/git/managers/github-web-flow.asc`).

## Audit log

The module writes one row to an audit profile for each essential action: a push, a delivery
installed (sync or runner), a pull (from the page when it brought commits in, or from a Designer),
Join remote history, a project added from or linked to its repository, a remote added, changed or
removed in a Designer, a branch switch, a layout switch, a restore, a Designer's checkout of a
commit or revert, a policy change, a credential added or removed, and a mode override created or
removed. Each row carries the user, the project (or `gateway`), the branch and the commit; a row
from a Designer has the Designer's host as the actor host and Designer as its context.
Nothing is written per file, per poll, per fetch or per auto-commit, and refusals are not audited;
those stay on the Logs tab. **Policy › Audit log** picks the profile: by default the gateway's own
audit profile (Gateway Audit Profile under Security › General Settings), so nothing is written when the gateway has none; or Off. An audit
write never holds up the git action; a profile that cannot be written to is logged once.

## Commit trailer

When a mode is active, commits the gateway and the Designer make end with

```text
Gateway-Mode: Prod
```

so the history shows where each change was made.

## Not per mode

The layout and the config repository's Delivery live in `data/.git/config`, so
they belong to the gateway, not the mode. A hand-written policy override that
leaves a setting out reads it as the default (allowed), never as off.
