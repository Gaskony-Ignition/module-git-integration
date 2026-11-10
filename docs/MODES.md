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

**Versioning › Policy** lists what the module may do on this gateway, each
setting's value and where it comes from (`from core` or `from Prod`).

| Setting | Off means |
| --- | --- |
| Push to remotes | The page's Remote Sync, the Designer's Push and a project's first push from Initialise are refused. |
| Force push | The Designer no longer offers to force push after a rejected push. |
| Commit from the Designer | Commit, amend, revert and completing a merge are refused in the Designer. The gateway's own config auto-commits carry on. |
| Create, switch and delete branches | The Designer's branch actions and the page's Switch to this branch are refused. |
| Switch the repository layout | The layout switch in Configure Versioning is refused. |
| Deliveries | Only the listed kinds (Sync — pull, Sync — replace, Runner) can be chosen, and the timer and the runner refuse the others. Off is always allowed. |
| Allowed remote hosts | Blank allows any. Otherwise every remote URL is checked before the gateway contacts it, and when a remote is saved or tested. `*.example.com` matches any subdomain. |

The default is how earlier versions behaved: everything allowed, any host.

A refused action stays on screen, disabled, with the reason: on the page as
text, in the Designer in the menu or button ("Checkout (off in deployment mode
Prod)"). The gateway refuses it either way — a route answers 403 and the
Designer shows the message, for example *Not allowed in deployment mode Prod:
pushing is turned off by the gateway's Git policy.*

## Per-mode remotes and credentials

When a mode is active, the drawers for the config remote, credentials, a
project's delivery and runner access have **Save to: core | Prod**. Core is the
value every mode uses unless it has its own; Prod writes an override that only
this mode uses. Each drawer shows where the value comes from now, and **Reset
to core** deletes the mode's override. A record that exists only in the mode
(a credential added there) is deleted by Reset, as core has nothing to fall
back to.

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
3. Still on Dev, edit the policy with **Save to: Prod**: Push off, Commit from
   the Designer off, branches and layout off, deliveries Sync — replace only,
   allowed hosts `github.com`. Commit and push; the override is in the branch.
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
- an HTTPS credential whose host field names a host (`github.com`,
  `*.example.com`) only goes to that host. A host field holding a label with no
  dot binds nothing;
- testing a saved credential against a URL on another host asks for the secret
  again.

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
