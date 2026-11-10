package com.operametrix.ignition.git;

import com.operametrix.ignition.git.automation.GitEvent;
import com.operametrix.ignition.git.automation.GitEvents;
import com.operametrix.ignition.git.managers.*;
import com.operametrix.ignition.git.records.GitSyncRecord;
import com.operametrix.ignition.git.records.GitProjectsConfigRecord;
import com.operametrix.ignition.git.records.GitRemoteCredentialsRecord;
import com.operametrix.ignition.git.records.GitReposUsersRecord;
import com.operametrix.ignition.git.records.GitUserHttpsCredentialRecord;
import com.operametrix.ignition.git.records.GitUserSshKeyRecord;
import com.inductiveautomation.ignition.common.BasicDataset;
import com.inductiveautomation.ignition.common.Dataset;
import com.inductiveautomation.ignition.common.util.DatasetBuilder;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.operametrix.ignition.git.managers.GitImageManager.exportImages;
import static com.operametrix.ignition.git.managers.GitManager.*;
import static com.operametrix.ignition.git.managers.GitTagManager.exportTag;
import static com.operametrix.ignition.git.managers.GitThemeManager.exportTheme;

// Declares GitScriptInterface directly (not just via AbstractScriptModule): the 8.3
// RpcDelegate discovers @RpcInterface only on impl.getClass().getInterfaces() and does
// not walk superclasses, so the interface must be on the concrete class itself.
public class GatewayScriptModule extends AbstractScriptModule implements GitScriptInterface {
    private final LoggerEx logger = LoggerEx.newBuilder().build(getClass());
    private final GatewayContext context;

    GatewayScriptModule(GatewayContext context) {
        this.context = context;
    }

    // ── Event detail lookups ───────────────────────────────────────────────────────────────────
    // Best-effort by design: an event carries context, so failing to read the branch must degrade
    // the event rather than fail the git operation that raised it.

    /** The working tree a project's git operations run in (the data dir, in Combined). */
    private static Path root(String projectName) {
        return GitManager.repoFor(projectName).root();
    }

    /**
     * Combined layout: what a pull, checkout or merge of the gateway repository changed reaches
     * every project and config, not only the one whose Designer asked. Config is rescanned and
     * the changed projects reloaded.
     */
    private static void afterGatewayWide(String projectName, org.eclipse.jgit.lib.ObjectId before) {
        try (Git git = getGit(root(projectName))) {
            org.eclipse.jgit.lib.ObjectId after = git.getRepository().resolve("HEAD");
            List<String> changed = before == null || after == null ? null
                    : ProjectScope.changedBetween(git.getRepository(), before, after);
            if (changed == null || changed.stream().anyMatch(p -> !p.startsWith("projects/"))) {
                ConfigAutoCommitter.hold(10_000);
                DataDirGitManager.applyConfigToRunningGateway();
            }
            DataDirGitManager.applyProjects(changed);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static org.eclipse.jgit.lib.ObjectId headOf(Path root) {
        try (Git git = getGit(root)) {
            return git.getRepository().resolve("HEAD");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Combined: the gateway repository's branch usually has no upstream (it was made by
     * {@code git init}, and the remote added afterwards), so pull and push use the branch set for
     * the gateway remote, as the Versioning page's Push does. Null when the checked-out branch has
     * an upstream of its own, or no gateway remote is set.
     */
    private static String gatewayBranchWithoutUpstream(Git git) throws java.io.IOException {
        String branch = git.getRepository().getBranch();
        if (branch != null && new org.eclipse.jgit.lib.BranchConfig(git.getRepository().getConfig(), branch)
                .getTrackingBranch() != null) {
            return null;
        }
        com.operametrix.ignition.git.records.GitConfigRemoteRecord gw =
                com.operametrix.ignition.git.records.GitConfigRemoteRecord.get();
        return gw == null || gw.getBranch() == null || gw.getBranch().isBlank() ? null : gw.getBranch();
    }

    /** Read-only in Combined: the one remote belongs to the gateway, set on the Versioning page. */
    private static void refuseInCombined(String what) {
        if (GitManager.combined()) {
            throw new RuntimeException(what + " is not available here: this gateway keeps every project"
                    + " in one repository (Combined layout), with one remote set on the gateway's"
                    + " Versioning page (Configure Versioning).");
        }
    }

    private String branchQuietly(String projectName) {
        try {
            return GitManager.getCurrentBranch(root(projectName));
        } catch (Exception e) {
            return "";
        }
    }

    private String headQuietly(String projectName) {
        try (Git git = getGit(root(projectName))) {
            org.eclipse.jgit.lib.ObjectId head = git.getRepository().resolve("HEAD");
            return head == null ? "" : head.getName();
        } catch (Exception e) {
            return "";
        }
    }

    /** The remote's URL, so a trigger can derive owner/repo; falls back to its name. */
    private String remoteUrlQuietly(String projectName, String remoteName) {
        try {
            String url = GitManager.getRemoteUrl(root(projectName), remoteName);
            return url == null || url.isBlank() ? remoteName : url;
        } catch (Exception e) {
            return remoteName;
        }
    }

    @Override
    public boolean pullImpl(String projectName,
                            String userName,
                            String remoteName,
                            boolean importTags,
                            boolean importTheme,
                            boolean importImages) throws Exception {
        try {
            boolean ok = doPull(projectName, userName, remoteName, importTags, importTheme, importImages);
            GitEvents.fire(GitEvent.of(GitEvent.PULL)
                    .project(projectName).user(userName).remote(remoteName)
                    .branch(branchQuietly(projectName)).commit(headQuietly(projectName))
                    .message("Pulled from " + remoteName).success());
            return ok;
        } catch (Exception e) {
            GitEvents.fire(GitEvent.of(GitEvent.PULL)
                    .project(projectName).user(userName).remote(remoteName)
                    .branch(branchQuietly(projectName)).failure(GitEvents.reason(e)));
            throw e;
        }
    }

    private boolean doPull(String projectName,
                           String userName,
                           String remoteName,
                           boolean importTags,
                           boolean importTheme,
                           boolean importImages) throws Exception {

        if (!projectHasRemote(projectName)) {
            throw new RuntimeException("No remote repository configured. Add a remote before pulling.");
        }

        RepoLocator.Repo repo = GitManager.repoFor(projectName);
        if (repo.scoped()) {
            org.eclipse.jgit.lib.ObjectId before = headOf(repo.root());
            synchronized (GitManager.lockFor(repo)) {
                try (Git git = getGit(repo.root())) {
                    PullCommand pull = git.pull().setRemote(remoteName);
                    String upstream = gatewayBranchWithoutUpstream(git);
                    if (upstream != null) {
                        pull.setRemoteBranchName(upstream);
                    }
                    setAuthentication(pull, projectName, userName, remoteName);
                    PullResult result = pull.call();
                    if (!result.isSuccessful()) {
                        if (result.getMergeResult() != null && result.getMergeResult().getMergeStatus()
                                == MergeResult.MergeStatus.CONFLICTING) {
                            List<String> files = new ArrayList<>();
                            for (String f : git.status().call().getConflicting()) {
                                files.add(repo.toProjectPath(f));
                            }
                            throw new RuntimeException("MERGE_CONFLICT:" + String.join("\n", files));
                        }
                        throw new RuntimeException("Pull failed: " + (result.getMergeResult() != null
                                ? result.getMergeResult().getMergeStatus().toString() : "unknown status"));
                    }
                }
            }
            afterGatewayWide(projectName, before);
            importSnapshots(projectName, importTags, importTheme, importImages);
            return true;
        }

        try (Git git = getGit(getProjectFolderPath(projectName))) {
            if (git.getRepository().resolve("HEAD") == null) {
                // An init that never reached its first checkout: finish it instead of
                // asking the remote for a branch this repo does not have.
                checkoutRemote(projectName, userName, remoteName, null, git);
                return true;
            }
            PullCommand pull = git.pull();
            pull.setRemote(remoteName);
            setAuthentication(pull, projectName, userName, remoteName);

            PullResult result = pull.call();
            if (!result.isSuccessful()) {
                // Check specifically for merge conflicts
                if (result.getMergeResult() != null
                        && result.getMergeResult().getMergeStatus()
                            == MergeResult.MergeStatus.CONFLICTING) {
                    Set<String> conflicting = git.status().call().getConflicting();
                    String fileList = String.join("\n", conflicting);
                    throw new RuntimeException("MERGE_CONFLICT:" + fileList);
                }
                throw new RuntimeException("Pull failed: " +
                        (result.getMergeResult() != null
                                ? result.getMergeResult().getMergeStatus().toString()
                                : "unknown status"));
            }

            logger.info("Pull was successful.");
            GitProjectManager.importProject(projectName);

            if (importTags) {
                GitTagManager.importTagManager(projectName);
            }
            if (importTheme) {
                GitThemeManager.importTheme(projectName);
            }
            if (importImages) {
                GitImageManager.importImages(projectName);
            }
        } catch (GitAPIException e) {
            logger.error(e.toString());
            throw new RuntimeException(e);
        }
        return true;
    }

    private static void importSnapshots(String projectName, boolean importTags, boolean importTheme,
                                        boolean importImages) throws Exception {
        if (importTags) {
            GitTagManager.importTagManager(projectName);
        }
        if (importTheme) {
            GitThemeManager.importTheme(projectName);
        }
        if (importImages) {
            GitImageManager.importImages(projectName);
        }
    }

    @Override
    protected boolean pushImpl(String projectName, String userName, String remoteName, boolean pushAllBranches, boolean pushTags, boolean forcePush) throws Exception {
        try {
            boolean ok = doPush(projectName, userName, remoteName, pushAllBranches, pushTags, forcePush);
            GitEvents.fire(GitEvent.of(GitEvent.PUSH)
                    .project(projectName).user(userName)
                    .remote(remoteUrlQuietly(projectName, remoteName))
                    .branch(branchQuietly(projectName)).commit(headQuietly(projectName))
                    .message("Pushed to " + remoteName).success());
            return ok;
        } catch (Exception e) {
            GitEvents.fire(GitEvent.of(GitEvent.PUSH)
                    .project(projectName).user(userName)
                    .remote(remoteUrlQuietly(projectName, remoteName))
                    .branch(branchQuietly(projectName)).failure(GitEvents.reason(e)));
            throw e;
        }
    }

    private boolean doPush(String projectName, String userName, String remoteName, boolean pushAllBranches, boolean pushTags, boolean forcePush) throws Exception {
        if (!projectHasRemote(projectName)) {
            throw new RuntimeException("No remote repository configured. Add a remote before pushing.");
        }

        try (Git git = getGit(root(projectName))) {
            PushCommand push = git.push();
            push.setRemote(remoteName);
            setAuthentication(push, projectName, userName, remoteName);
            String upstream = GitManager.combined() ? gatewayBranchWithoutUpstream(git) : null;
            if (upstream != null && !pushAllBranches) {
                // The Versioning page's Push does the same: HEAD to the gateway remote's branch.
                push.setRefSpecs(new RefSpec("HEAD:refs/heads/" + upstream));
            }

            if (pushAllBranches) {
                push.setPushAll();
            }
            if (pushTags) {
                push.setPushTags();
            }
            if (forcePush) {
                push.setForce(true);
            }
            Iterable<PushResult> results = push.call();
            for (PushResult result : results) {
                logger.trace(result.getMessages());
                for (org.eclipse.jgit.transport.RemoteRefUpdate update : result.getRemoteUpdates()) {
                    org.eclipse.jgit.transport.RemoteRefUpdate.Status status = update.getStatus();
                    if (status == org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD) {
                        throw new RuntimeException("REJECTED_NONFASTFORWARD: Push rejected — the remote contains commits that the local branch does not have. "
                                + "This typically happens after amending a commit that was already pushed.");
                    } else if (status == org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_NODELETE
                            || status == org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED
                            || status == org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_OTHER_REASON) {
                        throw new RuntimeException("Push rejected: " + update.getMessage());
                    }
                }
            }

        } catch (GitAPIException e) {
            logger.error(e.toString(), e);
            throw new RuntimeException(e);
        }
        return true;
    }

    @Override
    protected boolean commitImpl(String projectName, String userName, List<String> changes, String message, boolean amend) {
        try {
            boolean ok = doCommit(projectName, userName, changes, message, amend);
            GitEvents.fire(GitEvent.of(GitEvent.COMMIT)
                    .project(projectName).user(userName).branch(branchQuietly(projectName))
                    .commit(headQuietly(projectName)).message(message)
                    .files(changes == null ? List.of() : changes).success());
            return ok;
        } catch (RuntimeException e) {
            GitEvents.fire(GitEvent.of(GitEvent.COMMIT)
                    .project(projectName).user(userName).branch(branchQuietly(projectName))
                    .files(changes == null ? List.of() : changes)
                    .failure(GitEvents.reason(e)));
            throw e;
        }
    }

    private boolean doCommit(String projectName, String userName, List<String> changes, String message, boolean amend) {
        if (message == null || message.trim().isEmpty()) {
            throw new RuntimeException("Commit message cannot be empty.");
        }
        if (!amend && (changes == null || changes.isEmpty())) {
            throw new RuntimeException("Nothing to commit — select at least one file.");
        }
        RepoLocator.Repo repo = GitManager.repoFor(projectName);
        if (repo.scoped()) {
            synchronized (GitManager.lockFor(repo)) {
                try (Git git = getGit(repo.root())) {
                    ProjectScope.commit(git, repo, changes, message, amend, userName,
                            GitManager.resolveUserEmail(userName));
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    logger.error(e.toString(), e);
                    throw new RuntimeException(e);
                }
            }
            return true;
        }
        try (Git git = getGit(getProjectFolderPath(projectName))) {
            for (String change : changes) {
                git.add().addFilepattern(change).call();
                git.add().setUpdate(true).addFilepattern(change).call();
            }

            CommitCommand commit = git.commit().setMessage(message);
            if (amend) {
                commit.setAmend(true);
            }
            setCommitAuthor(commit, projectName, userName);
            commit.call();
        } catch (GitAPIException e) {
            logger.error(e.toString(), e);
            throw new RuntimeException(e);
        }
        return true;
    }

    @Override
    public Dataset getUncommitedChangesImpl(String projectName, String userName) {
        RepoLocator.Repo repo = GitManager.repoFor(projectName);
        Path projectPath = repo.root();
        Dataset ds;
        List<String> changes = new ArrayList<>();
        DatasetBuilder builder = new DatasetBuilder();
        builder.colNames(List.of("resource", "type", "actor", "timestamp"));
        builder.colTypes(List.of(String.class, String.class, String.class, String.class));

        try (Git git = getGit(projectPath)) {
            ProjectScope.requireNoNestedRepo(repo);
            Status status = ProjectScope.status(git, repo);

            // Filtered on repo paths (they read HEAD), then made project-relative for the Designer.
            Set<String> missing = repo.inProject(status.getMissing());
            Set<String> uncommittedChanges = repo.inProject(GitManager.filterJsonOrderingChanges(
                    git.getRepository(), projectPath, status.getUncommittedChanges()));
            Set<String> untracked = repo.inProject(status.getUntracked());
            Set<String> modified = repo.inProject(GitManager.filterJsonOrderingChanges(
                    git.getRepository(), projectPath, status.getChanged()));

            // Build union of all changed files for cross-category sibling detection
            Set<String> allChanged = new HashSet<>();
            allChanged.addAll(missing);
            allChanged.addAll(uncommittedChanges);
            allChanged.addAll(untracked);
            allChanged.addAll(modified);

            // Filter metadata-only changes from each set
            missing = GitManager.filterMetadataOnlyChanges(allChanged, missing);
            uncommittedChanges = GitManager.filterMetadataOnlyChanges(allChanged, uncommittedChanges);
            untracked = GitManager.filterMetadataOnlyChanges(allChanged, untracked);
            modified = GitManager.filterMetadataOnlyChanges(allChanged, modified);

            logger.debug("Missing files: {}" + missing);
            uncommittedChangesBuilder(projectName, missing, "Deleted", changes, builder);

            logger.debug("Uncommitted changes: {}" + uncommittedChanges);
            uncommittedChangesBuilder(projectName, uncommittedChanges, "Uncommitted", changes, builder);

            logger.debug("Untracked files: {}" + untracked);
            uncommittedChangesBuilder(projectName, untracked, "Created", changes, builder);

            logger.debug("Modified files: {}" + modified);
            uncommittedChangesBuilder(projectName, modified, "Modified", changes, builder);
        } catch (Exception e) {
            logger.error(e.toString(), e);

        }
        ds = builder.build();

        return ds != null ? ds : new BasicDataset();
    }

    @Override
    public boolean isRegisteredUserImpl(String projectName, String userName) {
        if (GitManager.combined()) {
            // No per-user records: the gateway repository authenticates with its own credential.
            return DataDirGitManager.isInitialized();
        }
        boolean registered;
        try {
            GitProjectsConfigRecord gitProjectsConfigRecord = getGitProjectConfigRecord(projectName);
            getGitReposUserRecord(gitProjectsConfigRecord, userName);
            registered = true;
        } catch (Exception e) {
            registered = false;
        }
        return registered;
    }

    @Override
    protected boolean snapshotTagsImpl(String projectName) {
        exportTag(getProjectFolderPath(projectName));
        return true;
    }

    @Override
    protected boolean snapshotThemesImpl(String projectName) {
        exportTheme(getProjectFolderPath(projectName));
        return true;
    }

    @Override
    protected boolean snapshotImagesImpl(String projectName) {
        exportImages(getProjectFolderPath(projectName), GitProjectsConfigRecord.imagePrefixFor(projectName));
        return true;
    }

    @Override
    public void setupLocalRepoImpl(String projectName, String userName) throws Exception {
        // .git/config is the source of truth for the repo and its remotes. The working
        // directory is materialized at registration time (initializeProject /
        // initializeLocalProject), so this idempotent startup guard is a no-op once the
        // repo exists. If it is missing there is no persisted URL to re-clone from — the
        // project must be re-registered through the setup wizard.
        Path path = getProjectFolderPath(projectName).resolve(".git");
        if (!GitManager.combined() && !Files.exists(path)) {
            logger.warn("Git working directory missing for registered project '" + projectName
                    + "'. Re-run repository setup to reconstruct it.");
        }
    }

    /**
     * Create the local working directory at registration time. Called by the init RPCs,
     * which supply the clone URL directly — nothing is persisted; {@code .git/config}
     * subsequently owns the remote configuration.
     *
     * A {@code .git} left behind by an earlier attempt that failed before its first
     * checkout (a rejected credential, say) has no HEAD commit. It is finished here
     * rather than skipped: skipping it registered the project on an unborn branch,
     * every resource showed as a change, and a pull then asked the remote for a
     * branch it never had. A {@code .git} this call created is removed on failure so
     * the retry starts clean.
     *
     * @param repoUri the clone URL, or {@code null}/empty for a local-only repository
     */
    private void materializeRepo(String projectName, String userName, String repoUri, String branch)
            throws Exception {
        Path projectFolderPath = getProjectFolderPath(projectName);
        Path gitDir = projectFolderPath.resolve(".git");
        boolean created = !Files.exists(gitDir);
        try (Git git = created
                ? Git.init().setDirectory(projectFolderPath.toFile()).call()
                : getGit(projectFolderPath)) {
            if (!created && git.getRepository().resolve("HEAD") != null) {
                return;
            }
            disableSsl(git);
            if (repoUri == null || repoUri.isEmpty()) {
                // Local-only repo: just create .git
                return;
            }
            URIish uri = new URIish(repoUri);
            if (git.getRepository().getConfig().getSubsections("remote").contains("origin")) {
                git.remoteSetUrl().setRemoteName("origin").setRemoteUri(uri).call();
            } else {
                git.remoteAdd().setName("origin").setUri(uri).call();
            }
            checkoutRemote(projectName, userName, "origin", branch, git);
        } catch (Exception e) {
            logger.error("An error occurred while setting up local repo for '" + projectName + "' project.", e);
            if (created) {
                deleteRecursively(gitDir);
            }
            throw e;
        }
    }

    /**
     * Bring an unborn repository up to {@code branch} on the named remote (its default branch when
     * {@code branch} is blank), or push the current folder up when that remote is empty.
     *
     * <p>The remote is a parameter rather than a hardcoded {@code origin} because
     * {@link #pullImpl} routes here for an unborn repository and carries the remote the caller
     * actually asked for — using origin regardless would silently pull from the wrong place.
     */
    private void checkoutRemote(String projectName, String userName, String remoteName, String branch,
                                Git git) throws Exception {
        // Lightweight ls-remote to detect the default branch without downloading objects
        LsRemoteCommand lsRemote = git.lsRemote().setRemote(remoteName).setHeads(true);
        setAuthentication(lsRemote, projectName, userName, remoteName);
        java.util.Collection<Ref> remoteRefs = lsRemote.call();
        if (remoteRefs.isEmpty()) {
            // Empty remote — push current project as initial content
            setupGitFromCurrentFolder(projectName, userName, git);
            return;
        }
        String defaultBranch;
        if (branch == null || branch.isBlank()) {
            defaultBranch = detectDefaultBranchFromRefs(remoteRefs);
        } else {
            defaultBranch = branch.trim();
            if (remoteRefs.stream().noneMatch(r -> r.getName().equals("refs/heads/" + defaultBranch))) {
                throw new RuntimeException("Branch '" + defaultBranch + "' is not on " + remoteName
                        + ". Branches there: " + String.join(", ", remoteRefs.stream()
                                .map(Ref::getName).filter(n -> n.startsWith("refs/heads/"))
                                .map(n -> n.substring("refs/heads/".length())).sorted().toList()));
            }
        }
        FetchCommand fetch = git.fetch()
                .setRemote(remoteName)
                .setRefSpecs(new RefSpec("+refs/heads/" + defaultBranch
                        + ":refs/remotes/" + remoteName + "/" + defaultBranch))
                .setDepth(1);
        setAuthentication(fetch, projectName, userName, remoteName);
        fetch.call();
        requireRootProjectJson(git, remoteName, defaultBranch);
        setupGitFromRemoteRepo(projectName, remoteName, defaultBranch, git);
        // Unshallow to pull full commit history for the History panel
        FetchCommand unshallow = git.fetch()
                .setRemote(remoteName)
                .setUnshallow(true);
        setAuthentication(unshallow, projectName, userName, remoteName);
        unshallow.call();
    }

    /**
     * The checkout that follows cleans and hard-resets the project folder, so a branch without
     * project.json at its root would wipe the gateway's project and leave a folder Ignition (and
     * the Projects list) no longer treats as a project. Checked against the fetched tree, before
     * anything on disk changes, so the caller's rollback leaves the project as it was.
     */
    private void requireRootProjectJson(Git git, String remoteName, String branch) throws Exception {
        org.eclipse.jgit.lib.Repository repo = git.getRepository();
        org.eclipse.jgit.lib.ObjectId tip = repo.resolve("refs/remotes/" + remoteName + "/" + branch);
        try (org.eclipse.jgit.revwalk.RevWalk walk = new org.eclipse.jgit.revwalk.RevWalk(repo);
             org.eclipse.jgit.treewalk.TreeWalk tw = org.eclipse.jgit.treewalk.TreeWalk.forPath(
                     repo, "project.json", walk.parseCommit(tip).getTree())) {
            if (tw == null) {
                throw new RuntimeException("Branch '" + branch + "' has no project.json at its root, so it"
                        + " is not an Ignition project. Pick the branch that holds the project at its"
                        + " root. Nothing was changed.");
            }
        }
    }

    private void deleteRecursively(Path root) {
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException ignored) {
                }
            });
        } catch (java.io.IOException e) {
            logger.warn("Could not remove " + root + " after a failed init", e);
        }
    }

    @Override
    protected List<String> getLocalBranchesImpl(String projectName) throws Exception {
        return GitManager.listLocalBranches(root(projectName));
    }

    @Override
    protected List<String> getRemoteBranchesImpl(String projectName) throws Exception {
        return GitManager.listRemoteBranches(root(projectName));
    }

    @Override
    protected String getCurrentBranchImpl(String projectName) throws Exception {
        return GitManager.getCurrentBranch(root(projectName));
    }

    @Override
    protected boolean createBranchImpl(String projectName, String branchName) throws Exception {
        return GitManager.createBranch(root(projectName), branchName);
    }

    @Override
    protected boolean checkoutBranchImpl(String projectName, String branchName) throws Exception {
        RepoLocator.Repo repo = GitManager.repoFor(projectName);
        if (repo.scoped()) {
            org.eclipse.jgit.lib.ObjectId before = headOf(repo.root());
            boolean result;
            synchronized (GitManager.lockFor(repo)) {
                ConfigAutoCommitter.hold(30_000);
                result = GitManager.checkoutBranch(repo.root(), branchName);
            }
            afterGatewayWide(projectName, before);
            return result;
        }
        boolean result = GitManager.checkoutBranch(getProjectFolderPath(projectName), branchName);
        GitProjectManager.importProject(projectName);
        return result;
    }

    @Override
    protected String deleteBranchImpl(String projectName, String branchName, boolean force) throws Exception {
        BranchHygiene.Result r = GitManager.deleteBranch(root(projectName), branchName, force,
                protectedBranches(projectName));
        return r.ok() ? "" : BranchDeleteResult.encode(r.kind(), r.count(), r.message());
    }

    @Override
    protected Dataset getLocalBranchInfoImpl(String projectName) throws Exception {
        List<BranchHygiene.Info> infos = GitManager.localBranchInfo(root(projectName),
                protectedBranches(projectName));
        DatasetBuilder builder = new DatasetBuilder();
        builder.colNames(List.of("name", "upstream", "gone", "ahead", "merged", "protectedReason"));
        builder.colTypes(List.of(String.class, String.class, Boolean.class, Integer.class, Boolean.class,
                String.class));
        for (BranchHygiene.Info i : infos) {
            builder.addRow(i.name(), i.upstream(), i.gone(), i.ahead(), i.merged(), i.protectedReason());
        }
        Dataset ds = builder.build();
        return ds != null ? ds : new BasicDataset();
    }

    @Override
    protected Dataset cleanupBranchesImpl(String projectName, List<String> branchNames) throws Exception {
        DatasetBuilder builder = new DatasetBuilder();
        builder.colNames(List.of("branch", "ok", "reason"));
        builder.colTypes(List.of(String.class, Boolean.class, String.class));
        Map<String, String> guarded = protectedBranches(projectName);
        for (String name : branchNames) {
            try {
                BranchHygiene.Result r = GitManager.deleteBranch(root(projectName), name, false,
                        guarded);
                builder.addRow(name, r.ok(), r.ok() ? "" : r.message());
            } catch (Exception e) {
                builder.addRow(name, false, String.valueOf(e.getMessage()));
            }
        }
        Dataset ds = builder.build();
        return ds != null ? ds : new BasicDataset();
    }

    /**
     * Branches a delivery follows: deleting one would break it. Separate: the project's scheduled
     * sync (its delivery branch is stored on the same record). Combined: the gateway delivery's
     * branch, since a project has no delivery of its own there.
     */
    private static Map<String, String> protectedBranches(String projectName) {
        Map<String, String> out = new HashMap<>();
        if (GitManager.combined()) {
            ConfigSync.Settings gw = ConfigSync.get();
            if (!ConfigSync.MODE_OFF.equals(gw.mode()) && !gw.branch().isBlank()) {
                out.put(gw.branch(), "delivery");
            }
            return out;
        }
        GitSyncRecord sync = GitSyncRecord.findByProject(projectName);
        if (sync != null && sync.isEnabled() && !sync.getBranch().isBlank()) {
            out.put(sync.getBranch(), "sync");
        }
        return out;
    }

    @Override
    protected String getRepositoryLayoutImpl() {
        return GitManager.combined() ? "combined" : "separate";
    }

    @Override
    protected boolean isProjectRegisteredImpl(String projectName) {
        if (GitManager.combined()) {
            return DataDirGitManager.isInitialized()
                    && Files.isRegularFile(getProjectFolderPath(projectName).resolve("project.json"));
        }
        try {
            getGitProjectConfigRecord(projectName);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected List<String> getResourceDiffImpl(String projectName, String resourcePath) {
        return GitManager.getResourceDiffContent(projectName, resourcePath);
    }

    @Override
    protected Dataset getCommitHistoryImpl(String projectName, int skip, int limit) {
        java.util.List<String[]> commits = GitManager.getCommitLog(GitManager.repoFor(projectName), skip, limit);
        DatasetBuilder builder = new DatasetBuilder();
        builder.colNames(java.util.List.of("hash", "shortHash", "author", "date", "message", "refs"));
        builder.colTypes(java.util.List.of(String.class, String.class, String.class, String.class, String.class, String.class));
        for (String[] row : commits) {
            builder.addRow((Object[]) row);
        }
        Dataset ds = builder.build();
        return ds != null ? ds : new BasicDataset();
    }

    @Override
    protected List<String> getCommitFilesImpl(String projectName, String commitHash) {
        RepoLocator.Repo repo = GitManager.repoFor(projectName);
        List<String> files = new ArrayList<>();
        for (String entry : GitManager.getCommitFileList(repo.root(), commitHash)) {
            int colon = entry.indexOf(':');
            String path = entry.substring(colon + 1);
            if (repo.contains(path)) {
                files.add(entry.substring(0, colon + 1) + repo.toProjectPath(path));
            }
        }
        return GitManager.filterMetadataOnlyCommitFiles(files);
    }

    @Override
    protected List<String> getCommitFileDiffImpl(String projectName, String commitHash, String filePath) {
        RepoLocator.Repo repo = GitManager.repoFor(projectName);
        return GitManager.getCommitFileDiffContent(repo.root(), commitHash, repo.toRepoPath(filePath));
    }

    @Override
    protected boolean discardChangesImpl(String projectName, List<String> paths) {
        RepoLocator.Repo repo = GitManager.repoFor(projectName);
        boolean result;
        synchronized (GitManager.lockFor(repo)) {
            result = GitManager.discardChanges(repo.root(), paths.stream().map(repo::toRepoPath).toList());
        }
        if (result) {
            GitProjectManager.importProject(projectName);
        }
        return result;
    }

    @Override
    protected boolean checkoutCommitImpl(String projectName, String commitHash) throws Exception {
        RepoLocator.Repo repo = GitManager.repoFor(projectName);
        if (repo.scoped()) {
            org.eclipse.jgit.lib.ObjectId before = headOf(repo.root());
            boolean result;
            synchronized (GitManager.lockFor(repo)) {
                ConfigAutoCommitter.hold(30_000);
                result = GitManager.checkoutCommit(repo.root(), commitHash);
            }
            afterGatewayWide(projectName, before);
            return result;
        }
        boolean result = GitManager.checkoutCommit(getProjectFolderPath(projectName), commitHash);
        GitProjectManager.importProject(projectName);
        return result;
    }

    @Override
    protected boolean revertCommitImpl(String projectName, String commitHash) throws Exception {
        RepoLocator.Repo repo = GitManager.repoFor(projectName);
        boolean result;
        synchronized (GitManager.lockFor(repo)) {
            result = GitManager.revertCommit(repo, commitHash);
        }
        if (result) {
            GitProjectManager.importProject(projectName);
        }
        return result;
    }

    @Override
    protected boolean initializeProjectImpl(String projectName, String repoUri, String ignitionUser,
                                             long sshKeyId, long httpsCredentialId) throws Exception {
        return initializeProject(projectName, repoUri, ignitionUser, sshKeyId, httpsCredentialId, null);
    }

    /** As {@link #initializeProjectImpl}, cloning {@code branch} instead of the remote's default. */
    public boolean initializeProject(String projectName, String repoUri, String ignitionUser,
                                     long sshKeyId, long httpsCredentialId, String branch) throws Exception {
        refuseInCombined("Linking a project to its own repository");
        // Check project not already registered
        if (isProjectRegisteredImpl(projectName)) {
            throw new Exception("Project '" + projectName + "' is already registered.");
        }

        // Create project config record
        GitProjectsConfigRecord projectRecord = new GitProjectsConfigRecord();
        projectRecord.setProjectName(projectName);
        projectRecord.save();

        // Create user registration record
        GitReposUsersRecord userRecord = new GitReposUsersRecord();
        userRecord.setProjectId(projectRecord.getId());
        userRecord.setIgnitionUser(ignitionUser);
        userRecord.save();

        // Create per-remote credential record for "origin" with FK refs to user-level credentials
        GitRemoteCredentialsRecord remoteCreds = new GitRemoteCredentialsRecord();
        remoteCreds.setProjectId(projectRecord.getId());
        remoteCreds.setIgnitionUser(ignitionUser);
        remoteCreds.setRemoteName("origin");
        if (sshKeyId > 0) {
            remoteCreds.setSshKeyId(sshKeyId);
        }
        if (httpsCredentialId > 0) {
            remoteCreds.setHttpsCredentialId(httpsCredentialId);
        }
        remoteCreds.save();

        // Attempt to initialize the local repo
        try {
            materializeRepo(projectName, ignitionUser, repoUri, branch);
        } catch (Exception e) {
            // Rollback: delete all records on failure
            try {
                remoteCreds.delete();
            } catch (Exception ignored) {
            }
            try {
                userRecord.delete();
            } catch (Exception ignored) {
            }
            try {
                projectRecord.delete();
            } catch (Exception ignored) {
            }
            throw e;
        }

        return true;
    }

    @Override
    protected boolean initializeLocalProjectImpl(String projectName, String ignitionUser) throws Exception {
        refuseInCombined("Creating a repository for one project");
        if (isProjectRegisteredImpl(projectName)) {
            throw new Exception("Project '" + projectName + "' is already registered.");
        }

        // Create project config record (registration marker; remotes live in .git/config)
        GitProjectsConfigRecord projectRecord = new GitProjectsConfigRecord();
        projectRecord.setProjectName(projectName);
        projectRecord.save();

        // Create user registration record
        GitReposUsersRecord userRecord = new GitReposUsersRecord();
        userRecord.setProjectId(projectRecord.getId());
        userRecord.setIgnitionUser(ignitionUser);
        userRecord.save();

        // Initialize local repo: git init + add . + initial commit
        try {
            Path projectFolderPath = getProjectFolderPath(projectName);
            try (Git git = Git.init().setDirectory(projectFolderPath.toFile()).call()) {
                disableSsl(git);
                git.add().addFilepattern(".").call();

                CommitCommand commit = git.commit().setMessage("Initial commit");
                setCommitAuthor(commit, projectName, ignitionUser);
                commit.call();
            }
        } catch (Exception e) {
            // Rollback: delete both records on failure
            try {
                userRecord.delete();
            } catch (Exception ignored) {
            }
            try {
                projectRecord.delete();
            } catch (Exception ignored) {
            }
            throw e;
        }

        return true;
    }

    @Override
    protected boolean hasRemoteRepositoryImpl(String projectName) {
        try {
            return projectHasRemote(projectName);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Whether the project's git repo has any remote configured in {@code .git/config}.
     * Unlike {@link GitProjectsConfigRecord#hasRemote()} — which only reflects the record's
     * stored URI (populated at init for the cloned remote) — this inspects the live git
     * config, so user-named (non-origin) remotes added later are recognized.
     */
    private boolean projectHasRemote(String projectName) throws Exception {
        return !GitManager.listRemotes(root(projectName)).isEmpty();
    }

    @Override
    protected Dataset listRemotesImpl(String projectName) throws Exception {
        List<String[]> remotes = GitManager.listRemotes(root(projectName));
        DatasetBuilder builder = new DatasetBuilder();
        builder.colNames(List.of("name", "url"));
        builder.colTypes(List.of(String.class, String.class));
        for (String[] row : remotes) {
            builder.addRow((Object[]) row);
        }
        Dataset ds = builder.build();
        return ds != null ? ds : new BasicDataset();
    }

    @Override
    protected boolean addRemoteImpl(String projectName, String remoteName, String remoteUrl,
                                     String ignitionUser) throws Exception {
        refuseInCombined("Adding a remote");
        Path projectPath = getProjectFolderPath(projectName);
        GitManager.addRemote(projectPath, remoteName, remoteUrl);

        // Create empty credential record — FK refs are attached separately via setRemoteCredentialRef
        GitProjectsConfigRecord projectRecord = getGitProjectConfigRecord(projectName);
        GitRemoteCredentialsRecord creds = new GitRemoteCredentialsRecord();
        creds.setProjectId(projectRecord.getId());
        creds.setIgnitionUser(ignitionUser);
        creds.setRemoteName(remoteName);
        creds.save();

        return true;
    }

    @Override
    protected boolean removeRemoteImpl(String projectName, String remoteName,
                                        String ignitionUser) throws Exception {
        refuseInCombined("Removing a remote");
        Path projectPath = getProjectFolderPath(projectName);
        GitManager.removeRemote(projectPath, remoteName);

        // Delete credential record
        GitProjectsConfigRecord projectRecord = getGitProjectConfigRecord(projectName);
        GitRemoteCredentialsRecord creds = GitRemoteCredentialsRecord.findByProjectUserRemote(
                projectRecord.getId(), ignitionUser, remoteName);
        if (creds != null) {
            creds.delete();
        }

        return true;
    }

    @Override
    protected boolean setRemoteUrlImpl(String projectName, String remoteName, String newUrl,
                                        String ignitionUser) throws Exception {
        refuseInCombined("Changing a remote");
        Path projectPath = getProjectFolderPath(projectName);
        GitManager.setRemoteUrl(projectPath, remoteName, newUrl);

        // Ensure a credential record exists for this remote (FK refs are set via setRemoteCredentialRef)
        GitProjectsConfigRecord projectRecord = getGitProjectConfigRecord(projectName);
        GitRemoteCredentialsRecord creds = GitRemoteCredentialsRecord.findByProjectUserRemote(
                projectRecord.getId(), ignitionUser, remoteName);
        if (creds == null) {
            creds = new GitRemoteCredentialsRecord();
            creds.setProjectId(projectRecord.getId());
            creds.setIgnitionUser(ignitionUser);
            creds.setRemoteName(remoteName);
            creds.save();
        }

        return true;
    }

    @Override
    protected List<String> getConflictingFilesImpl(String projectName) {
        RepoLocator.Repo repo = GitManager.repoFor(projectName);
        // Every conflict, not only this project's: a merge cannot be completed with one hidden.
        // Files outside the project come back with a leading "/" (see RepoLocator.Repo).
        return GitManager.getConflictingFiles(repo.root()).stream().map(repo::toProjectPath).toList();
    }

    @Override
    protected boolean resolveConflictImpl(String projectName, String filePath, String stage) {
        RepoLocator.Repo repo = GitManager.repoFor(projectName);
        return GitManager.resolveConflict(repo.root(), repo.toRepoPath(filePath), stage);
    }

    @Override
    protected boolean abortMergeImpl(String projectName) {
        RepoLocator.Repo repo = GitManager.repoFor(projectName);
        if (repo.scoped()) {
            org.eclipse.jgit.lib.ObjectId before = headOf(repo.root());
            boolean result;
            synchronized (GitManager.lockFor(repo)) {
                ConfigAutoCommitter.hold(30_000);
                result = GitManager.abortMerge(repo.root());
            }
            // HEAD does not move, but the conflicted files on disk go back to it, config included.
            afterGatewayWide(projectName, null);
            return result;
        }
        boolean result = GitManager.abortMerge(getProjectFolderPath(projectName));
        if (result) {
            GitProjectManager.importProject(projectName);
        }
        return result;
    }

    @Override
    protected boolean completeMergeCommitImpl(String projectName, String userName) throws Exception {
        RepoLocator.Repo repo = GitManager.repoFor(projectName);
        if (repo.scoped()) {
            boolean result;
            synchronized (GitManager.lockFor(repo)) {
                result = GitManager.completeMergeCommit(repo.root(), projectName, userName);
            }
            afterGatewayWide(projectName, null);
            return result;
        }
        boolean result = GitManager.completeMergeCommit(getProjectFolderPath(projectName), projectName, userName);
        if (result) {
            GitProjectManager.importProject(projectName);
        }
        return result;
    }

    @Override
    protected List<String> getConflictDiffImpl(String projectName, String filePath) {
        RepoLocator.Repo repo = GitManager.repoFor(projectName);
        return GitManager.getConflictDiffContent(repo.root(), repo.toRepoPath(filePath));
    }

    @Override
    protected boolean fetchImpl(String projectName, String userName, String remoteName) throws Exception {
        if (!projectHasRemote(projectName)) {
            throw new RuntimeException("No remote repository configured. Add a remote before fetching.");
        }

        try (Git git = getGit(root(projectName))) {
            FetchCommand fetch = git.fetch();
            fetch.setRemote(remoteName);
            fetch.setUnshallow(true);
            // Drop remote-tracking refs for branches deleted on the remote, so they stop listing.
            fetch.setRemoveDeletedRefs(true);
            setAuthentication(fetch, projectName, userName, remoteName);
            fetch.call();
            logger.info("Fetch from '" + remoteName + "' was successful.");
        } catch (GitAPIException e) {
            logger.error(e.toString(), e);
            throw new RuntimeException(e);
        }
        return true;
    }

    private void setupGitFromCurrentFolder(String projectName, String userName, Git git) throws Exception {
        try {
            git.add().addFilepattern(".").call();

            CommitCommand commit = git.commit().setMessage("Initial commit");
            setCommitAuthor(commit, projectName, userName);
            commit.call();

            PushCommand pushCommand = git.push();

            setAuthentication(pushCommand, projectName, userName, "origin");

            String branch = git.getRepository().getBranch();
            pushCommand.setRemote("origin").setRefSpecs(new RefSpec(branch)).call();
        } catch (GitAPIException e) {
            logger.error(e.toString());
            throw new RuntimeException(e);
        }
    }

    private void setupGitFromRemoteRepo(String projectName, String remoteName, String defaultBranch,
                                        Git git) throws Exception {
        try {
            CheckoutCommand checkout = git.checkout()
                    .setName(defaultBranch)
                    .setCreateBranch(true)
                    .setForced(true)
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                    .setStartPoint(remoteName + "/" + defaultBranch);
            checkout.call();

            // Without cleanDirectories this is `git clean -f`, which leaves a whole
            // untracked folder alone: a view the remote deleted survived a clone
            // and showed as Added on the pulling gateway.
            git.clean().setForce(true).setCleanDirectories(true).call();
            git.reset().setMode(ResetCommand.ResetType.HARD).call();

            GitProjectManager.importProject(projectName);

            GitTagManager.importTagManager(projectName);

            GitThemeManager.importTheme(projectName);

            GitImageManager.importImages(projectName);
        } catch (GitAPIException e) {
            logger.error(e.toString());
            throw new RuntimeException(e);
        }
    }

    /**
     * Detect the default branch from ls-remote refs (lightweight, no data transfer needed).
     * Checks for HEAD symref target first, then falls back to common names.
     */
    private String detectDefaultBranchFromRefs(java.util.Collection<Ref> refs) {
        // Check for well-known default branch names among the heads
        java.util.Set<String> refNames = new java.util.HashSet<>();
        for (Ref ref : refs) {
            refNames.add(ref.getName());
        }
        for (String candidate : new String[]{"main", "master", "develop"}) {
            if (refNames.contains("refs/heads/" + candidate)) {
                return candidate;
            }
        }
        // Last resort: first branch ref found
        for (Ref ref : refs) {
            if (ref.getName().startsWith("refs/heads/")) {
                return ref.getName().substring("refs/heads/".length());
            }
        }
        return "main";
    }

    private Path getProjectFolderPath(String projectName) {
        Path dataDir = context.getSystemManager().getDataDir().toPath();
        return dataDir.resolve("projects").resolve(projectName);
    }

    // ── User-level SSH key management ─────────────────────────────────

    @Override
    protected boolean saveUserSshKeyImpl(String ignitionUser, String keyName, String sshKey) {
        try {
            // Update an existing key with the same name in place, otherwise create a new one.
            GitUserSshKeyRecord record = GitUserSshKeyRecord.findByUserAndKeyName(ignitionUser, keyName);
            if (record == null) {
                record = new GitUserSshKeyRecord();
                record.setIgnitionUser(ignitionUser);
                record.setKeyName(keyName);
            }
            record.setSSHKey(sshKey);
            record.save();
            return true;
        } catch (Exception e) {
            logger.error("Error saving user SSH key", e);
            return false;
        }
    }

    @Override
    protected boolean deleteUserSshKeyImpl(String ignitionUser, long keyId) {
        try {
            GitUserSshKeyRecord record = GitUserSshKeyRecord.findByIdAndUser(keyId, ignitionUser);
            if (record == null) return false;

            // Clear FK references in remote credential records pointing to this key
            List<GitRemoteCredentialsRecord> refs = GitRemoteCredentialsRecord.listBySshKeyId(keyId);
            for (GitRemoteCredentialsRecord ref : refs) {
                ref.setSshKeyId(0);
                ref.save();
            }

            record.delete();
            return true;
        } catch (Exception e) {
            logger.error("Error deleting user SSH key", e);
            return false;
        }
    }

    @Override
    protected Dataset listUserSshKeysImpl(String ignitionUser) {
        try {
            List<GitUserSshKeyRecord> keys = GitUserSshKeyRecord.listByUser(ignitionUser);
            DatasetBuilder builder = new DatasetBuilder();
            builder.colNames("id", "keyName");
            builder.colTypes(Long.class, String.class);
            for (GitUserSshKeyRecord key : keys) {
                builder.addRow(key.getId(), key.getKeyName());
            }
            return builder.build();
        } catch (Exception e) {
            logger.error("Error listing user SSH keys", e);
            return new BasicDataset();
        }
    }

    // ── User-level HTTPS credential management ────────────────────────

    @Override
    protected boolean saveUserHttpsCredentialImpl(String ignitionUser, String hostPattern,
                                                   String userName, String password) {
        try {
            // Check if a credential already exists for this user+host
            GitUserHttpsCredentialRecord existing =
                    GitUserHttpsCredentialRecord.findByUserAndHostPattern(ignitionUser, hostPattern);
            if (existing != null) {
                existing.setUserName(userName);
                if (password != null && !password.isEmpty()) {
                    existing.setPassword(password);
                }
                existing.save();
            } else {
                GitUserHttpsCredentialRecord record = new GitUserHttpsCredentialRecord();
                record.setIgnitionUser(ignitionUser);
                record.setHostPattern(hostPattern);
                record.setUserName(userName);
                record.setPassword(password);
                record.save();
            }
            return true;
        } catch (Exception e) {
            logger.error("Error saving user HTTPS credential", e);
            return false;
        }
    }

    @Override
    protected boolean deleteUserHttpsCredentialImpl(String ignitionUser, long credentialId) {
        try {
            GitUserHttpsCredentialRecord record =
                    GitUserHttpsCredentialRecord.findByIdAndUser(credentialId, ignitionUser);
            if (record == null) return false;

            // Clear FK references in remote credential records pointing to this credential
            List<GitRemoteCredentialsRecord> refs =
                    GitRemoteCredentialsRecord.listByHttpsCredentialId(credentialId);
            for (GitRemoteCredentialsRecord ref : refs) {
                ref.setHttpsCredentialId(0);
                ref.save();
            }

            record.delete();
            return true;
        } catch (Exception e) {
            logger.error("Error deleting user HTTPS credential", e);
            return false;
        }
    }

    @Override
    protected Dataset listUserHttpsCredentialsImpl(String ignitionUser) {
        try {
            List<GitUserHttpsCredentialRecord> creds =
                    GitUserHttpsCredentialRecord.listByUser(ignitionUser);
            DatasetBuilder builder = new DatasetBuilder();
            builder.colNames("id", "hostPattern", "userName");
            builder.colTypes(Long.class, String.class, String.class);
            for (GitUserHttpsCredentialRecord cred : creds) {
                builder.addRow(cred.getId(), cred.getHostPattern(), cred.getUserName());
            }
            return builder.build();
        } catch (Exception e) {
            logger.error("Error listing user HTTPS credentials", e);
            return new BasicDataset();
        }
    }

    // ── Remote credential association ─────────────────────────────────

    @Override
    protected boolean setRemoteCredentialRefImpl(String projectName, String remoteName,
                                                  String ignitionUser, long sshKeyId,
                                                  long httpsCredentialId) {
        if (GitManager.combined()) {
            logger.warn("Not attaching a credential to project '" + projectName + "': the Combined layout"
                    + " uses the gateway repository's credential (Configure Versioning).");
            return false;
        }
        try {
            GitRemoteCredentialsRecord creds = getRemoteCredentialsRecord(
                    projectName, ignitionUser, remoteName);
            if (creds == null) {
                // Create a minimal record to hold the FK reference
                GitProjectsConfigRecord projectRecord = getGitProjectConfigRecord(projectName);
                creds = new GitRemoteCredentialsRecord();
                creds.setProjectId(projectRecord.getId());
                creds.setIgnitionUser(ignitionUser);
                creds.setRemoteName(remoteName);
            }
            creds.setSshKeyId(sshKeyId);
            creds.setHttpsCredentialId(httpsCredentialId);
            creds.save();
            return true;
        } catch (Exception e) {
            logger.error("Error setting remote credential reference", e);
            return false;
        }
    }

    @Override
    protected Dataset getRemoteCredentialRefImpl(String projectName, String remoteName,
                                                 String ignitionUser) {
        DatasetBuilder builder = new DatasetBuilder();
        builder.colNames(List.of("sshKeyId", "httpsCredentialId"));
        builder.colTypes(List.of(Long.class, Long.class));
        try {
            if (GitManager.combined()) {
                com.operametrix.ignition.git.records.GitConfigRemoteRecord gw =
                        com.operametrix.ignition.git.records.GitConfigRemoteRecord.get();
                builder.addRow(gw == null ? 0L : gw.getSshKeyId(), gw == null ? 0L : gw.getHttpsCredentialId());
                return builder.build();
            }
            GitRemoteCredentialsRecord creds = getRemoteCredentialsRecord(
                    projectName, ignitionUser, remoteName);
            long sshKeyId = creds != null ? creds.getSshKeyId() : 0L;
            long httpsCredentialId = creds != null ? creds.getHttpsCredentialId() : 0L;
            builder.addRow(sshKeyId, httpsCredentialId);
        } catch (Exception e) {
            logger.error("Error getting remote credential reference", e);
            builder.addRow(0L, 0L);
        }
        Dataset ds = builder.build();
        return ds != null ? ds : new BasicDataset();
    }
}
