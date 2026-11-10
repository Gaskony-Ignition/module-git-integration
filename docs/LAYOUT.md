# Repository layout and branches

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
Projects tab has a Branch column with ↑/↓ against the upstream and a
**Branches** button on each versioned project's row; the Versioning page has a
**Branches** tab for the gateway config. With no remote set, the view says so and
links to where it is set. Each draws the commit graph beside the branch list,
with gone, merged, ahead and behind marked. In Combined the project view shows
the gateway repository and dims commits that leave that project. **Switch to
this branch** changes it from the page; it is refused, with the files, while
there is uncommitted work or while a sync or delivery follows another branch.

![A project's Branches tab: commit graph and branch list](images/versioning-branches.png)

In Combined, a revert from the Designer is refused when the commit also changed
files outside that project, rather than undoing config or another project's
work from a window that cannot show it. Config's auto-commit and **Restore**
leave `projects/` alone. A runner release and a deleted project are each
committed for that project's folder only. An open Designer follows a layout or
branch switch within ten seconds, without reopening.

![The Repository layout setting](images/versioning-layout.png)

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

## Using an existing repository

The module does not remap folders: the gateway repository has `config/` at the
top and, in Combined, `projects/<name>/`. A repository laid out another way —
say `gateway/config/` and `project/` — is refused before anything is pushed to
it or pulled from it, as is a branch whose history is unrelated to the
gateway's. Restructure it once, on your side:

```text
git mv gateway/config config
git mv project projects/<ProjectName>
git commit -m "Gateway repository layout"
git push
```

Then bring the two histories together once: set the gateway's remote branch to a
new name (say `gateway-import`) and Remote Sync, which pushes a branch the remote
does not have yet; in a clone, merge it into the restructured branch with
`git merge --allow-unrelated-histories origin/gateway-import`, push, and set the
gateway's branch back to that one. From then on the histories are related.

New repositories start on the configured remote branch (default `main`). One
made before 4.2.0 may sit on `master` while the remote's branch is `main`; the
Versioning header (or the project's Branch column) then offers **Rename local
master to main** — same commits, upstream kept.

## Designer branch list

The **Branch** button opens local and remote lists. A fetch drops remote
branches that were deleted on the remote, and the local list marks a branch
`[gone]` (its remote branch was deleted), `[merged]` (already in the current
branch's upstream, else HEAD) or `↑3` (three commits on no remote). Delete refuses
the checked-out branch and the branch a scheduled sync follows. A branch with
unmerged or unpushed commits asks again, with the commit count, before **Delete
anyway**. **Clean up…** fetches, then offers every gone or merged branch, ticked.

**Pull** fetches with prune first. If the checked-out branch's remote branch was
deleted — usually because it was merged — it says so and offers **Switch to main
and pull** (the remote's default branch, else the configured one, else main or
master), then offers to delete the local branch if it is merged, or says how many
commits it would lose. The sync timer and the runner only log a warning.

![Branch list with gone and merged marks, and Clean up](images/designer-branch-cleanup.png)
