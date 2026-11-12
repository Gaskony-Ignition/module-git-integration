package com.operametrix.ignition.git.actions;

import com.operametrix.ignition.git.BranchDeleteResult;
import com.operametrix.ignition.git.InitProgressDialog;
import com.operametrix.ignition.git.PolicyRefusals;
import com.operametrix.ignition.git.PullText;
import com.inductiveautomation.ignition.client.util.gui.ErrorUtil;
import com.inductiveautomation.ignition.common.resourcecollection.ChangeOperation;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceId;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.operametrix.ignition.git.DesignerHook.*;
import static com.operametrix.ignition.git.managers.GitActionManager.*;

public class GitBaseAction {
    private static final Logger logger = LoggerFactory.getLogger(GitBaseAction.class);

    public static void handleCommitAction(List<String> changes, String commitMessage, boolean amend) {
        try {
            rpc.commit(projectName, userName, changes, commitMessage, amend);
            if (instance != null) {
                instance.refreshCommitPanel();
                instance.refreshHistoryPanel();
            }
        } catch (Exception ex) {
            ErrorUtil.showError(ex);
        }
    }

    public static void handlePushAction(String remoteName) {
        if (!rpc.hasRemoteRepository(projectName)) {
            JOptionPane.showMessageDialog(context.getFrame(),
                    "No remote repository configured. Add a remote before pushing.",
                    "Push", JOptionPane.WARNING_MESSAGE);
            return;
        }

        InitProgressDialog progress = new InitProgressDialog(context.getFrame(), "Pushing");
        new SwingWorker<Void, Void>() {
            private boolean rejected = false;

            @Override
            protected Void doInBackground() throws Exception {
                progress.setStatus("Pushing to " + remoteName + "...");
                try {
                    rpc.push(projectName, userName, remoteName, false, false, false);
                } catch (Exception ex) {
                    String exMsg = ex.getMessage() != null ? ex.getMessage() : "";
                    if (exMsg.contains("REJECTED_NONFASTFORWARD")) {
                        rejected = true;
                    } else {
                        throw ex;
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                progress.complete();
                try {
                    get();
                    if (rejected && PolicyRefusals.reason("FORCE_PUSH") != null) {
                        JOptionPane.showMessageDialog(context.getFrame(),
                                "Push was rejected because the remote contains commits not present locally.\n"
                                        + "Force push is " + PolicyRefusals.reason("FORCE_PUSH")
                                        + ": pull and push again instead.",
                                "Push Rejected", JOptionPane.WARNING_MESSAGE);
                    } else if (rejected) {
                        int choice = JOptionPane.showConfirmDialog(
                                context.getFrame(),
                                "Push was rejected because the remote contains commits not present locally.\n"
                                        + "This typically happens after amending a pushed commit.\n\n"
                                        + "Do you want to force push? This will overwrite the remote branch.",
                                "Push Rejected",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.WARNING_MESSAGE
                        );
                        if (choice == JOptionPane.YES_OPTION) {
                            handleForcePush(remoteName);
                            return;
                        }
                    }
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    showPlainError(cause);
                } finally {
                    if (instance != null) {
                        instance.refreshCommitPanel();
                        instance.refreshHistoryPanel();
                    }
                }
            }
        }.execute();
        progress.setVisible(true);
    }

    private static void handleForcePush(String remoteName) {
        InitProgressDialog progress = new InitProgressDialog(context.getFrame(), "Force Pushing");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                progress.setStatus("Force pushing to " + remoteName + "...");
                rpc.push(projectName, userName, remoteName, false, false, true);
                return null;
            }

            @Override
            protected void done() {
                progress.complete();
                try {
                    get();
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    showPlainError(cause);
                } finally {
                    if (instance != null) {
                        instance.refreshCommitPanel();
                        instance.refreshHistoryPanel();
                    }
                }
            }
        }.execute();
        progress.setVisible(true);
    }

    public static void handlePullAction(String remoteName, boolean importTags, boolean importTheme, boolean importImages) {
        if (offerOpenMerge() || !noUnsavedChanges("Pull")) {
            return;
        }
        handlePullAction(remoteName, importTags, importTheme, importImages, null);
    }

    /**
     * @param leftBranch a branch this pull was switched away from because its remote branch was
     *                   deleted; once the pull succeeds, deleting it locally is offered
     */
    private static void handlePullAction(String remoteName, boolean importTags, boolean importTheme,
                                         boolean importImages, String leftBranch) {
        if (!rpc.hasRemoteRepository(projectName)) {
            JOptionPane.showMessageDialog(context.getFrame(),
                    "No remote repository configured. Add a remote before pulling.",
                    "Pull", JOptionPane.WARNING_MESSAGE);
            return;
        }

        InitProgressDialog progress = new InitProgressDialog(context.getFrame(), "Pulling");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                progress.setStatus("Pulling from " + remoteName + "...");
                rpc.pull(projectName, userName, remoteName, importTags, importTheme, importImages);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    progress.setStatus("Syncing project to Designer...");
                    pullProjectFromGateway();
                    progress.complete();
                    if (leftBranch != null) {
                        SwingUtilities.invokeLater(() -> offerDeleteLeftBranch(leftBranch));
                    }
                } catch (Exception e) {
                    progress.complete();
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    String exMsg = cause.getMessage() != null ? cause.getMessage() : "";
                    if (exMsg.contains("MERGE_CONFLICT:")) {
                        String filesPart = exMsg.substring(exMsg.indexOf("MERGE_CONFLICT:") + "MERGE_CONFLICT:".length());
                        List<String> conflictingFiles = new ArrayList<>();
                        for (String f : filesPart.split("\n")) {
                            String trimmed = f.trim();
                            if (!trimmed.isEmpty()) {
                                conflictingFiles.add(trimmed);
                            }
                        }
                        showMergeConflictPopup(projectName, userName, conflictingFiles);
                    } else if (exMsg.contains(PullText.GONE_PREFIX)) {
                        String gone = exMsg.substring(exMsg.indexOf(PullText.GONE_PREFIX));
                        SwingUtilities.invokeLater(() -> offerSwitchAfterGone(gone, remoteName,
                                importTags, importTheme, importImages));
                    } else if (exMsg.startsWith("Not pulled:")) {
                        // ErrorUtil's dialog is a fixed 350px and cut the file list off mid-path;
                        // a message dialog grows to fit it.
                        JOptionPane.showMessageDialog(context.getFrame(), exMsg, "Pull",
                                JOptionPane.WARNING_MESSAGE);
                    } else {
                        showPlainError(cause);
                    }
                } finally {
                    if (instance != null) {
                        instance.refreshBranchLabel();
                        instance.refreshCommitPanel();
                        instance.refreshHistoryPanel();
                    }
                }
            }
        }.execute();
        progress.setVisible(true);
    }

    /**
     * The gateway's plain message, with the original exception under Details when it sent one
     * (after {@link PullText#DETAILS}), instead of a raw JGit exception as the headline.
     */
    public static void showPlainError(Throwable cause) {
        String m = cause.getMessage() == null ? cause.toString() : cause.getMessage();
        if (m.contains("Using an existing repository")) {
            // The layout refusal is a paragraph with the fix in it; ErrorUtil's fixed-width box cuts it.
            JOptionPane.showMessageDialog(context.getFrame(), wrap(m, 90), "Repository layout",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        int d = m.indexOf(PullText.DETAILS);
        if (d > 0) {
            ErrorUtil.showError(m.substring(0, d), new Exception(m.substring(d + PullText.DETAILS.length())));
        } else {
            ErrorUtil.showError(m, cause);
        }
    }

    private static String wrap(String text, int width) {
        StringBuilder out = new StringBuilder();
        int col = 0;
        for (String word : text.split(" ")) {
            if (col > 0 && col + word.length() > width) {
                out.append('\n');
                col = 0;
            } else if (col > 0) {
                out.append(' ');
                col++;
            }
            out.append(word);
            col += word.length();
        }
        return out.toString();
    }

    /**
     * The checked-out branch's remote branch was deleted (usually merged). Offers to switch to the
     * remote's default branch and pull it; switching is the Designer user's decision, never the
     * gateway's.
     */
    private static void offerSwitchAfterGone(String text, String remoteName, boolean importTags,
                                             boolean importTheme, boolean importImages) {
        String[] p = text.substring(PullText.GONE_PREFIX.length()).split("\t", 4);
        String branch = p[0];
        String target = p.length > 2 ? p[2] : "";
        String message = p.length > 3 ? p[3] : text;
        java.awt.Frame frame = context.getFrame();
        if (target.isEmpty()) {
            JOptionPane.showMessageDialog(frame, message + "\n\nThe remote has no main or master branch to"
                    + " switch to. Pick a branch with the Branch button.", "Pull", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String refused = PolicyRefusals.reason("BRANCH_SWITCH");
        if (refused != null) {
            JOptionPane.showMessageDialog(frame, message + "\n\n" + refused, "Pull",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        String scope = gatewayWide()
                ? "\n\nThis gateway keeps every project and its config in one repository, so this"
                + " switches all of them."
                : "";
        Object[] options = {"Switch to " + target + " and pull", "Cancel"};
        int choice = JOptionPane.showOptionDialog(frame, message + scope, "Pull",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        if (choice != 0) {
            return;
        }
        if (!noUnsavedChanges("Switch and pull")) {
            return;
        }
        try {
            closeAllEditorTabs();
            rpc.checkoutBranch(projectName, target);
        } catch (Exception ex) {
            showPlainError(ex.getCause() != null ? ex.getCause() : ex);
            return;
        }
        handlePullAction(remoteName, importTags, importTheme, importImages, branch);
    }

    /**
     * After switching away from a branch whose remote branch is gone: delete it locally when it is
     * merged (the gateway's non-force delete decides), else say what deleting it would lose.
     */
    private static void offerDeleteLeftBranch(String branch) {
        java.awt.Frame frame = context.getFrame();
        try {
            com.inductiveautomation.ignition.common.Dataset info = rpc.getLocalBranchInfo(projectName);
            boolean merged = false;
            int ahead = 0;
            boolean found = false;
            for (int r = 0; r < info.getRowCount(); r++) {
                if (branch.equals(info.getValueAt(r, "name"))) {
                    found = true;
                    merged = Boolean.TRUE.equals(info.getValueAt(r, "merged"));
                    Object a = info.getValueAt(r, "ahead");
                    ahead = a instanceof Number ? ((Number) a).intValue() : 0;
                }
            }
            if (!found) {
                return;
            }
            String current = rpc.getCurrentBranch(projectName);
            if (!merged) {
                JOptionPane.showMessageDialog(frame, branch + " is not merged into " + current
                        + (ahead > 0 ? ": deleting it would lose " + ahead + " commit(s) that are on no remote" : "")
                        + ".\nIt was kept. Delete it from the Branch list if you no longer need it.",
                        "Pull", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            int c = JOptionPane.showConfirmDialog(frame, branch + " is merged into " + current
                    + ".\nDelete the local branch " + branch + "?", "Pull", JOptionPane.YES_NO_OPTION);
            if (c != JOptionPane.YES_OPTION) {
                return;
            }
            BranchDeleteResult refused = BranchDeleteResult.parse(rpc.deleteBranch(projectName, branch, false));
            if (refused != null) {
                JOptionPane.showMessageDialog(frame, refused.message(), "Delete branch",
                        JOptionPane.WARNING_MESSAGE);
            }
        } catch (Exception ex) {
            showPlainError(ex.getCause() != null ? ex.getCause() : ex);
        }
    }

    public static void handleCheckoutAction(String branchName) {
        if (offerOpenMerge() || !noUnsavedChanges("Checkout")) {
            return;
        }
        if (gatewayWide()) {
            int choice = JOptionPane.showConfirmDialog(context.getFrame(),
                    // Line breaks of our own: a JOptionPane never wraps, and one line was 1468 px wide.
                    "Check out " + branchName + "?\n\nThis gateway keeps every project and its config in one"
                            + " repository,\nso this switches the gateway config and every project on it,"
                            + " not only this one.\nIt is refused while anything in that repository is"
                            + " uncommitted:\ncommit or discard this project's changes first.",
                    "Checkout Branch", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
        }
        try {
            closeAllEditorTabs();
            rpc.checkoutBranch(projectName, branchName);
            pullProjectFromGateway();
        } catch (Exception ex) {
            ErrorUtil.showError(ex);
        } finally {
            if (instance != null) {
                instance.refreshBranchLabel();
                instance.refreshCommitPanel();
                instance.refreshHistoryPanel();
            }
        }
    }

    private static void closeAllEditorTabs() {
        try {
            java.awt.Frame frame = context.getFrame();
            Method getWorkspace = frame.getClass().getMethod("getWorkspace");
            Object workspaceManager = getWorkspace.invoke(frame);

            Method getCount = workspaceManager.getClass().getMethod("getWorkspaceCount");
            Method getWs = workspaceManager.getClass().getMethod("getWorkspace", int.class);

            int count = (int) getCount.invoke(workspaceManager);
            for (int i = 0; i < count; i++) {
                Object ws = getWs.invoke(workspaceManager, i);
                Class<?> tabbedClass;
                try {
                    tabbedClass = Class.forName(
                            "com.inductiveautomation.ignition.designer.tabbedworkspace.TabbedResourceWorkspace");
                } catch (ClassNotFoundException e) {
                    return;
                }
                if (tabbedClass.isInstance(ws)) {
                    Method getEditors = tabbedClass.getMethod("getEditors");
                    Collection<?> editors = (Collection<?>) getEditors.invoke(ws);
                    List<?> editorsCopy = new ArrayList<>(editors);

                    // 8.3: TabbedResourceWorkspace.close now takes the resourcecollection
                    // ResourcePath (the resource model moved packages); looking it up with
                    // the old common.project.resource.ResourcePath threw NoSuchMethodException,
                    // leaving editors open so their stale content conflicted on pull.
                    Method closeMethod = tabbedClass.getMethod("close",
                            com.inductiveautomation.ignition.common.resourcecollection.ResourcePath.class,
                            boolean.class);

                    for (Object editor : editorsCopy) {
                        Method getResourcePath = editor.getClass().getMethod("getResourcePath");
                        Object path = getResourcePath.invoke(editor);
                        closeMethod.invoke(ws, path, false);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to close editor tabs", e);
        }
    }

    /** Unsaved Designer edits, as resource paths, including those still held in an open editor tab. */
    static List<ResourcePath> unsavedChanges() {
        commitOpenEditors();
        List<ResourcePath> out = new ArrayList<>();
        for (ChangeOperation op : context.getProject().getChanges()) {
            ResourceId rid = ChangeOperation.getResourceIdFromChange(op);
            if (rid != null) {
                out.add(rid.getResourcePath());
            }
        }
        return out;
    }

    /**
     * An edit in an open editor tab (a Perspective view, a script, a named query) reaches the
     * project's change list only when its editor commits it: on File > Save, or when the tab is
     * left. Until then getChanges() does not list it, so the unsaved-edit check missed it. This
     * commits every open tab of every tabbed workspace (TabbedResourceWorkspace.commitAll, which
     * Save runs too); an editor with nothing changed adds nothing. Not the Designer's whole
     * pre-save step: that also runs each module's notifyProjectSaveStart, which creates Vision's
     * and Perspective's default project resources and so reported edits nobody made.
     */
    private static void commitOpenEditors() {
        try {
            Class<?> tabbed = Class.forName(
                    "com.inductiveautomation.ignition.designer.tabbedworkspace.TabbedResourceWorkspace");
            Method commitAll = tabbed.getMethod("commitAll");
            Object manager = context.getFrame().getClass().getMethod("getWorkspace").invoke(context.getFrame());
            Method getWs = manager.getClass().getMethod("getWorkspace", int.class);
            int count = (int) manager.getClass().getMethod("getWorkspaceCount").invoke(manager);
            for (int i = 0; i < count; i++) {
                Object ws = getWs.invoke(manager, i);
                if (tabbed.isInstance(ws)) {
                    try {
                        commitAll.invoke(ws);
                    } catch (Exception e) {
                        logger.warn("Could not commit the open tabs of {}", ws.getClass().getSimpleName(), e);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Could not commit open editors before checking for unsaved edits", e);
        }
    }

    /**
     * Before an action that reloads the project from the gateway, which drops the Designer's
     * unsaved edits: true when there are none. Otherwise lists them and says to save first;
     * nothing is done either way, so no edit is lost to the reload.
     */
    public static boolean noUnsavedChanges(String action) {
        List<ResourcePath> unsaved = unsavedChanges();
        if (unsaved.isEmpty()) {
            return true;
        }
        StringBuilder m = new StringBuilder(action + " reloads this project from the gateway, which would"
                + " throw away these unsaved edits:\n\n");
        for (int i = 0; i < Math.min(15, unsaved.size()); i++) {
            m.append(unsaved.get(i)).append('\n');
        }
        if (unsaved.size() > 15) {
            m.append("... and ").append(unsaved.size() - 15).append(" more\n");
        }
        m.append("\nSave them first (File > Save), then try again. Nothing was changed.");
        // A plain OK: the SDK gives a module no way to save for the user.
        JOptionPane.showMessageDialog(context.getFrame(), m.toString(), action, JOptionPane.WARNING_MESSAGE);
        return false;
    }

    /**
     * A merge an earlier pull left open (the Designer was closed while it was in conflict) blocks
     * every later pull and checkout. When there is one, offer its conflict dialog, where it is
     * completed or aborted, and return true.
     */
    public static boolean offerOpenMerge() {
        List<String> files;
        try {
            files = rpc.getConflictingFiles(projectName);
        } catch (Exception e) {
            return false;
        }
        if (files == null || files.isEmpty()) {
            return false;
        }
        int c = JOptionPane.showConfirmDialog(context.getFrame(),
                "A merge from an earlier pull is still open, with " + files.size() + " file(s) in conflict."
                        + "\nNothing can be pulled or checked out until it is completed or aborted."
                        + "\n\nOpen it now?",
                "Merge in progress", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (c == JOptionPane.YES_OPTION) {
            showMergeConflictPopup(projectName, userName, files);
        }
        return true;
    }

    public static void pullProjectFromGateway() {
        pullProjectFromGateway(null);
    }

    /**
     * @param only the resource paths (as {@code ResourcePath.toString()}) whose Designer edits are
     *             dropped, or null for all of them
     */
    public static void pullProjectFromGateway(java.util.Set<String> only) {
        try {
            // 8.3 replacement for the removed DesignableProject.notifyPushComplete(List):
            // before pulling the gateway/branch state, discard any local Designer edits so
            // the pull adopts the gateway state wholesale instead of opening the Resolve
            // Conflicts dialog. The actions that get here refuse first while the Designer has
            // unsaved edits (noUnsavedChanges), so what is dropped is only stale state.
            var project = context.getProject();
            List<ResourcePath> stalePaths = new ArrayList<>();
            for (ChangeOperation op : project.getChanges()) {
                ResourceId rid = ChangeOperation.getResourceIdFromChange(op);
                if (rid != null && (only == null || only.contains(rid.getResourcePath().toString()))) {
                    stalePaths.add(rid.getResourcePath());
                }
            }
            for (ResourcePath p : stalePaths) {
                project.discardChanges(p);
            }

            // IgnitionDesigner.updateProject() (public) performs the full gateway pull +
            // Designer refresh — the 8.3 successor to the private no-arg pullAndResolve()
            // the pre-8.3 module reflected into (its signature changed in 8.3). Invoked
            // reflectively to avoid a compile-time dependency on the concrete frame class.
            java.awt.Frame frame = context.getFrame();
            Method updateProject = frame.getClass().getMethod("updateProject");
            updateProject.invoke(frame);

            // Best-effort repair after the in-place refresh (see repairModuleFolderNodes).
            repairModuleFolderNodes();
        } catch (Exception e) {
            logger.error("Failed to pull project from gateway", e);
            ErrorUtil.showError("Git updated the project on the gateway, but the Designer could not "
                    + "refresh automatically. The project view may be out of sync — update or reopen "
                    + "the project to load the latest state.", e);
        }
    }

    /**
     * Best-effort workaround for an Ignition Designer limitation exposed by in-place project
     * refresh. When {@code updateProject()} first introduces a module's resources into an
     * already-open project (e.g. a git pull/checkout/merge that adds Perspective content),
     * the incremental tree-update path can leave that module's namespace node
     * (e.g. {@code com.inductiveautomation.perspective}) registered as a <em>non-folder</em>.
     * A later save then fails inside Perspective's {@code SessionPropsAdapter} with
     * "Unable to create folder path, found non-folder in the way at
     * 'com.inductiveautomation.perspective'". Ignition's own {@code validateFolderStructure()}
     * self-repair explicitly skips module-level nodes, so it never heals this; only a full
     * project reload (constructor path) otherwise fixes it.
     *
     * <p>Here we mend just the broken node in place: {@code DesignerProjectTreeImpl}
     * (the concrete {@code DesignableProject}) keeps its module roots in a private
     * {@code moduleNodes} map; any entry that is not a folder is converted to one via the
     * tree's own {@code makeFolder}. All access is reflective — these are Designer internals,
     * not public API — and any failure is swallowed so the plain {@code updateProject()}
     * refresh remains the fallback (worst case: the pre-existing reopen-to-fix behaviour).
     */
    private static void repairModuleFolderNodes() {
        try {
            Object tree = context.getProject(); // DesignerProjectTreeImpl implements DesignableProject
            Class<?> treeClass = tree.getClass();

            java.lang.reflect.Field moduleNodesField = treeClass.getDeclaredField("moduleNodes");
            moduleNodesField.setAccessible(true);
            java.util.Map<?, ?> moduleNodes = (java.util.Map<?, ?>) moduleNodesField.get(tree);
            if (moduleNodes == null || moduleNodes.isEmpty()) {
                return;
            }

            Method makeFolder = treeClass.getDeclaredMethod("makeFolder", ResourcePath.class);
            makeFolder.setAccessible(true);

            boolean repairedAny = false;
            for (Object node : moduleNodes.values()) {
                if (node == null) {
                    continue;
                }
                Class<?> nodeClass = node.getClass();
                Method isFolder = nodeClass.getDeclaredMethod("isFolder");
                isFolder.setAccessible(true);
                if (Boolean.TRUE.equals(isFolder.invoke(node))) {
                    continue;
                }
                Method getPath = nodeClass.getDeclaredMethod("getPath");
                getPath.setAccessible(true);
                Object path = getPath.invoke(node);

                Object folderResource = makeFolder.invoke(tree, path);
                Method update = nodeClass.getDeclaredMethod("update",
                        com.inductiveautomation.ignition.common.resourcecollection.Resource.class);
                update.setAccessible(true);
                update.invoke(node, folderResource);
                repairedAny = true;
                logger.info("Repaired non-folder module node '{}' after in-place project refresh.", path);
            }

            if (repairedAny) {
                // Propagate the fix through the resource collection's effective state.
                Method updateEffectiveState = null;
                for (Class<?> c = treeClass; c != null && updateEffectiveState == null; c = c.getSuperclass()) {
                    try {
                        updateEffectiveState = c.getDeclaredMethod("updateEffectiveState");
                    } catch (NoSuchMethodException ignore) {
                        // walk up
                    }
                }
                if (updateEffectiveState != null) {
                    updateEffectiveState.setAccessible(true);
                    updateEffectiveState.invoke(tree);
                }
            }
        } catch (Throwable t) {
            logger.warn("Could not repair module folder nodes after project refresh; a save may fail "
                    + "with a 'non-folder in the way' error until the project is reopened: {}", t.toString());
        }
    }

    public static void handleCreateBranchAction(String branchName) {
        try {
            rpc.createBranch(projectName, branchName);
        } catch (Exception ex) {
            ErrorUtil.showError(ex);
            return;
        }
        handleCheckoutAction(branchName);
    }

    /**
     * Tries a safe delete first. Unmerged or unpushed work gets a second warning with the commit
     * count and a "Delete anyway"; a protected branch is only reported.
     */
    public static void handleDeleteBranchAction(String branchName) {
        try {
            BranchDeleteResult refused = BranchDeleteResult.parse(rpc.deleteBranch(projectName, branchName, false));
            if (refused == null) return;
            if (refused.kind().equals("unmerged") || refused.kind().equals("unpushed")) {
                String what = refused.kind().equals("unmerged")
                        ? "not merged into the current branch" : "not on any remote";
                Object[] options = {"Delete anyway", "Cancel"};
                int choice = JOptionPane.showOptionDialog(context.getFrame(),
                        "Branch '" + branchName + "' has " + refused.count() + " commit"
                                + (refused.count() == 1 ? "" : "s") + " " + what + ".\n"
                                + "Deleting it loses " + (refused.count() == 1 ? "that commit" : "those commits")
                                + " unless another branch or tag points at them.",
                        "Delete Unmerged Branch", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                        null, options, options[1]);
                if (choice == 0) {
                    BranchDeleteResult again = BranchDeleteResult.parse(rpc.deleteBranch(projectName, branchName, true));
                    if (again != null) {
                        JOptionPane.showMessageDialog(context.getFrame(), again.message(), "Delete Branch",
                                JOptionPane.WARNING_MESSAGE);
                    }
                }
            } else {
                JOptionPane.showMessageDialog(context.getFrame(), refused.message(), "Delete Branch",
                        JOptionPane.WARNING_MESSAGE);
            }
        } catch (Exception ex) {
            ErrorUtil.showError(ex);
        }
    }

    public static void handleCheckoutCommitAction(String commitHash, String shortHash) {
        if (offerOpenMerge() || !noUnsavedChanges("Checkout")) {
            return;
        }
        int choice = JOptionPane.showConfirmDialog(context.getFrame(),
                "Check out commit " + shortHash + "?\n\n"
                        + "This will put the repository in 'detached HEAD' state.\n"
                        + "Any new commits will not belong to a branch.\n"
                        + "To keep changes, create a new branch first."
                        + (gatewayWide()
                                ? "\n\nThis gateway keeps every project and its config in one repository,"
                                + "\nso this switches the gateway config and every project on it.\nIt is"
                                + " refused while anything in that repository is uncommitted."
                                : ""),
                "Checkout Commit", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

        if (choice != JOptionPane.YES_OPTION) return;

        try {
            closeAllEditorTabs();
            rpc.checkoutCommit(projectName, commitHash);
            pullProjectFromGateway();
        } catch (Exception ex) {
            ErrorUtil.showError(ex);
        } finally {
            if (instance != null) {
                instance.refreshBranchLabel();
                instance.refreshCommitPanel();
                instance.refreshHistoryPanel();
            }
        }
    }

    public static void handleFetchAction(String remoteName) {
        if (!rpc.hasRemoteRepository(projectName)) {
            JOptionPane.showMessageDialog(context.getFrame(),
                    "No remote repository configured. Add a remote before fetching.",
                    "Fetch", JOptionPane.WARNING_MESSAGE);
            return;
        }

        InitProgressDialog progress = new InitProgressDialog(context.getFrame(), "Fetching");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                progress.setStatus("Fetching from " + remoteName + "...");
                rpc.fetch(projectName, userName, remoteName);
                return null;
            }

            @Override
            protected void done() {
                progress.complete();
                try {
                    get();
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    showPlainError(cause);
                } finally {
                    if (instance != null) {
                        instance.refreshHistoryPanel();
                    }
                }
            }
        }.execute();
        progress.setVisible(true);
    }

    public static void handleRevertCommitAction(String commitHash, String shortHash, String message) {
        if (!noUnsavedChanges("Revert")) {
            return;
        }
        int choice = JOptionPane.showConfirmDialog(context.getFrame(),
                "Revert commit " + shortHash + "?\n\n\"" + message + "\"\n\n"
                        + "This will create a new commit that undoes the changes.",
                "Revert Commit", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

        if (choice != JOptionPane.YES_OPTION) return;

        try {
            rpc.revertCommit(projectName, userName, commitHash);
            pullProjectFromGateway();
        } catch (Exception ex) {
            ErrorUtil.showError(ex);
        } finally {
            if (instance != null) {
                instance.refreshCommitPanel();
                instance.refreshHistoryPanel();
            }
        }
    }

    public static void handleBranchAction() {
        try {
            showBranchPopup(projectName, userName);
            if (instance != null) {
                instance.refreshCommitPanel();
                instance.refreshHistoryPanel();
            }
        } catch (Exception ex) {
            ErrorUtil.showError(ex);
        }
    }
}
