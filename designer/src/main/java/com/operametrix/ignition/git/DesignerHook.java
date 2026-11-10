package com.operametrix.ignition.git;

import com.operametrix.ignition.git.actions.GitBaseAction;
import com.operametrix.ignition.git.managers.GitActionManager;
import com.operametrix.ignition.git.utils.IconUtils;
import com.inductiveautomation.ignition.client.gateway_interface.GatewayConnection;
import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.Dataset;
import com.inductiveautomation.ignition.common.ConcurrencySessionInfo;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.resourcecollection.ChangeOperation;

import com.inductiveautomation.ignition.designer.gui.StatusBar;
import com.inductiveautomation.ignition.designer.model.DesignerContext;
import com.inductiveautomation.ignition.designer.model.AbstractDesignerModuleHook;
import com.inductiveautomation.ignition.designer.model.SaveContext;

import com.jidesoft.docking.DockContext;
import com.jidesoft.docking.DockableFrame;
import com.jidesoft.docking.DockingManager;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;

public class DesignerHook extends AbstractDesignerModuleHook {
    private static final String PROJECT_BROWSER_KEY = "Project Browser";

    public static DesignerHook instance;
    public static GitScriptInterface rpc = GatewayConnection.getRpcInterface(
            GitScriptInterface.SERIALIZER,
            "com.operametrix.ignition.git",
            GitScriptInterface.class
    );
    public static List<ChangeOperation> changes = new ArrayList<>();
    public static DesignerContext context;
    public static String projectName;
    public static String userName;
    JPanel gitStatusBar;
    JButton branchButton;
    Timer gitUserTimer;
    /** The gateway's repository layout as last seen; re-read on the status poll. */
    private String layout;
    private JLabel layoutNotice;

    CommitPanel commitPanel;
    DockableFrame commitFrame;
    boolean commitFrameInitialized;
    Timer commitRefreshTimer;
    HistoryPanel historyPanel;
    DockableFrame historyFrame;
    boolean historyFrameInitialized;
    Timer panelVisibilityTimer;

    @Override
    public void startup(DesignerContext context, LicenseState activationState) throws Exception {
        super.startup(context, activationState);
        instance = this;
        DesignerHook.context = context;
        BundleUtil.get().addBundle("DesignerHook", getClass(), "DesignerHook");

        projectName = context.getProjectName();

        Optional<ConcurrencySessionInfo> sessionInfo = context.getResourceEditManager().getCurrentSessionInfo();
        userName = sessionInfo.map(ConcurrencySessionInfo::username).orElse("");

        try {
            layout = rpc.getRepositoryLayout();
        } catch (Exception e) {
            layout = null;
        }
        boolean registered = rpc.isProjectRegistered(projectName);
        if (registered) {
            rpc.setupLocalRepo(projectName, userName);
            initStatusBar();
            initCommitPanel();
            initHistoryPanel();
        } else {
            initStatusBarUnregistered();
        }

    }

    private void initStatusBar(){
        StatusBar statusBar = context.getStatusBar();
        gitStatusBar = new JPanel();

        // Branch button: git icon + branch name
        branchButton = new JButton();
        branchButton.setIcon(IconUtils.getIcon("/com/operametrix/ignition/git/icons/ic_git.svg"));
        try {
            branchButton.setText(rpc.getCurrentBranch(projectName));
        } catch (Exception e) {
            branchButton.setText("unknown");
        }
        branchButton.setFont(branchButton.getFont().deriveFont(Font.BOLD));
        branchButton.setToolTipText("Manage Branches");
        styleStatusBarButton(branchButton);
        branchButton.addActionListener(e -> GitBaseAction.handleBranchAction());
        gitStatusBar.add(branchButton);

        // Remotes button: filled cloud icon + "Remotes" label
        JButton remotesButton = new JButton("Remotes", IconUtils.getIcon("/com/operametrix/ignition/git/icons/ic_cloud_filled.svg"));
        remotesButton.setToolTipText("Manage Remotes");
        styleStatusBarButton(remotesButton);
        remotesButton.addActionListener(e -> GitActionManager.showRemotesPopup(projectName, userName));
        gitStatusBar.add(remotesButton);

        // User button: user icon + username
        boolean userValid = rpc.isRegisteredUser(projectName, userName);
        String userIconPath = userValid ? "/com/operametrix/ignition/git/icons/ic_verified_user.svg" : "/com/operametrix/ignition/git/icons/ic_unregister_user.svg";
        JButton userButton = new JButton(userName, IconUtils.getIcon(userIconPath));
        userButton.setToolTipText("Manage Git Credentials");
        styleStatusBarButton(userButton);
        userButton.addActionListener(e -> GitActionManager.showCredentialsPopup(projectName, userName));
        gitStatusBar.add(userButton);

        statusBar.addDisplay(gitStatusBar);

        gitUserTimer = new Timer(10000, e -> {
            boolean valid = rpc.isRegisteredUser(projectName, userName);
            String userIconPath1 = valid ? "/com/operametrix/ignition/git/icons/ic_verified_user.svg" : "/com/operametrix/ignition/git/icons/ic_unregister_user.svg";
            userButton.setIcon(IconUtils.getIcon(userIconPath1));

            try {
                String branch = rpc.getCurrentBranch(projectName);
                String shown = branchButton.getText();
                branchButton.setText(branch);
                if (branch != null && shown != null && !"unknown".equals(shown) && !shown.equals(branch)) {
                    followBranchSwitch(branch);
                }
            } catch (Exception ex) {
                branchButton.setText("unknown");
            }
            checkLayout();
        });

        gitUserTimer.start();
    }

    /**
     * The branch was switched from the gateway page. The gateway already holds the new branch's
     * resources, but the Designer only loads them on Update Project — so load them here, unless
     * that would throw away edits this Designer has not saved.
     */
    private void followBranchSwitch(String branch) {
        String notice;
        if (context.getProject().getChanges().isEmpty()) {
            GitBaseAction.pullProjectFromGateway();
            refreshCommitPanel();
            refreshHistoryPanel();
            notice = "Switched to '" + branch + "' on the gateway; the project was reloaded.";
        } else {
            notice = "Switched to '" + branch + "' on the gateway. Save or discard your edits, then File > Update Project.";
        }
        if (layoutNotice != null) {
            gitStatusBar.remove(layoutNotice);
        }
        layoutNotice = new JLabel(notice);
        gitStatusBar.add(layoutNotice);
        gitStatusBar.revalidate();
        gitStatusBar.repaint();
    }

    private void initStatusBarUnregistered() {
        StatusBar statusBar = context.getStatusBar();
        gitStatusBar = new JPanel();

        // Git icon + "Configure" — opens init wizard
        JButton notConfiguredButton = new JButton("Configure",
                IconUtils.getIcon("/com/operametrix/ignition/git/icons/ic_git.svg"));
        styleStatusBarButton(notConfiguredButton);
        notConfiguredButton.addActionListener(e -> GitActionManager.showInitRepoPopup(projectName, userName));
        gitStatusBar.add(notConfiguredButton);

        // User button — manage credentials before init
        boolean hasCredentials = hasUserCredentials(userName);
        String userIconPath = hasCredentials
                ? "/com/operametrix/ignition/git/icons/ic_verified_user.svg"
                : "/com/operametrix/ignition/git/icons/ic_unregister_user.svg";
        JButton userButton = new JButton(userName, IconUtils.getIcon(userIconPath));
        userButton.setToolTipText("Manage Git Credentials");
        styleStatusBarButton(userButton);
        userButton.addActionListener(e -> GitActionManager.showCredentialsPopup(projectName, userName));
        gitStatusBar.add(userButton);

        statusBar.addDisplay(gitStatusBar);

        // Poll to update user icon when credentials are added/removed
        gitUserTimer = new Timer(10000, e -> {
            boolean hasCreds = hasUserCredentials(userName);
            String iconPath = hasCreds
                    ? "/com/operametrix/ignition/git/icons/ic_verified_user.svg"
                    : "/com/operametrix/ignition/git/icons/ic_unregister_user.svg";
            userButton.setIcon(IconUtils.getIcon(iconPath));
            checkLayout();
        });
        gitUserTimer.start();
    }

    /**
     * An admin can switch the layout while this Designer is open. Branches, remotes and whether
     * this project is versioned all follow it, so pick it up here rather than on the next open.
     */
    private void checkLayout() {
        String now;
        try {
            now = rpc.getRepositoryLayout();
        } catch (Exception e) {
            return;
        }
        if (now == null || now.equals(layout)) {
            return;
        }
        boolean first = layout == null;
        layout = now;
        if (first) {
            return;
        }
        boolean combined = "combined".equals(now);
        if (branchButton == null && rpc.isProjectRegistered(projectName)) {
            reinitializeAfterSetup();
        } else {
            refreshBranchLabel();
            refreshCommitPanel();
            refreshHistoryPanel();
        }
        GitActionManager.onLayoutChanged(combined);
        if (gitStatusBar != null) {
            if (layoutNotice != null) {
                gitStatusBar.remove(layoutNotice);
            }
            layoutNotice = new JLabel(combined
                    ? "Layout is now Combined: branches and the remote are gateway-wide."
                    : "Layout is now Separate: this project has its own repository.");
            gitStatusBar.add(layoutNotice);
            gitStatusBar.revalidate();
            gitStatusBar.repaint();
        }
    }

    private boolean hasUserCredentials(String userName) {
        try {
            Dataset sshKeys = rpc.listUserSshKeys(userName);
            if (sshKeys != null && sshKeys.getRowCount() > 0) return true;
            Dataset httpsCreds = rpc.listUserHttpsCredentials(userName);
            if (httpsCreds != null && httpsCreds.getRowCount() > 0) return true;
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    private static void styleStatusBarButton(JButton button) {
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setMargin(new Insets(0, 0, 0, 0));
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setContentAreaFilled(true);
                button.setBorderPainted(true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                button.setContentAreaFilled(false);
                button.setBorderPainted(false);
            }
        });
    }

    public void reinitializeAfterSetup() {
        if (gitUserTimer != null) {
            gitUserTimer.stop();
        }

        StatusBar statusBar = context.getStatusBar();
        if (gitStatusBar != null) {
            statusBar.removeDisplay(gitStatusBar);
        }

        if (panelVisibilityTimer != null) {
            panelVisibilityTimer.stop();
            panelVisibilityTimer = null;
        }

        cleanupCommitPanel();
        cleanupHistoryPanel();

        initStatusBar();
        initCommitPanel();
        initHistoryPanel();
    }

    private void initCommitPanel() {
        commitPanel = new CommitPanel();
        GitActionManager.wireCommitPanel(commitPanel, projectName, userName);

        commitFrame = new DockableFrame("Commit",
                IconUtils.getIcon("/com/operametrix/ignition/git/icons/ic_commit.svg"));
        commitFrame.setTitle(BundleUtil.get().getStringLenient("DesignerHook.Commit.Title"));
        commitFrame.getContentPane().add(commitPanel);
        commitFrame.setPreferredSize(new Dimension(525, 400));
        commitFrame.setAutohideWidth(525);
        commitFrame.setDockedWidth(525);

        DockingManager dockingManager = context.getDockingManager();

        // Add frame initially hidden, then group it as a tab with the Project Browser
        commitFrame.setInitSide(DockContext.DOCK_SIDE_WEST);
        commitFrame.setInitIndex(0);
        commitFrame.setInitMode(DockContext.STATE_HIDDEN);
        dockingManager.addFrame(commitFrame);
        commitFrameInitialized = true;

        // Defer tab grouping until the Designer layout is fully initialized
        Timer dockTimer = new Timer(2000, e -> {
            DockableFrame projectBrowser = dockingManager.getFrame(PROJECT_BROWSER_KEY);
            dockingManager.showFrame(commitFrame.getKey());
            if (projectBrowser != null) {
                dockingManager.moveFrame(commitFrame.getKey(), PROJECT_BROWSER_KEY);
            }
            if (historyFrameInitialized) {
                dockingManager.showFrame(historyFrame.getKey());
                dockingManager.moveFrame(historyFrame.getKey(), PROJECT_BROWSER_KEY);
            }
            if (projectBrowser != null) {
                dockingManager.activateFrame(PROJECT_BROWSER_KEY);
            }
        });
        dockTimer.setRepeats(false);
        dockTimer.start();

        // Keep Commit/History panels visible across workspace switches.
        // Not all workspaces fire JIDE docking events for custom frames,
        // so we poll at a short interval and only act when needed.
        panelVisibilityTimer = new Timer(1000, e -> {
            if (!commitFrameInitialized && !historyFrameInitialized) return;
            DockingManager dm = context.getDockingManager();
            boolean needsRestore = false;
            if (commitFrameInitialized && commitFrame != null) {
                DockableFrame f = dm.getFrame(commitFrame.getKey());
                // isHidden() catches JIDE's logical hidden state; !isDisplayable()
                // catches frames removed from the Swing hierarchy by workspace switches
                // (some workspaces detach frames without using JIDE's hidden API)
                if (f == null || f.isHidden() || !f.isDisplayable()) needsRestore = true;
            }
            if (historyFrameInitialized && historyFrame != null) {
                DockableFrame f = dm.getFrame(historyFrame.getKey());
                if (f == null || f.isHidden() || !f.isDisplayable()) needsRestore = true;
            }
            if (needsRestore) {
                ensurePanelsVisible();
            }
        });
        panelVisibilityTimer.start();

        // Mark changed resources in the Project Browser, fed by the same refresh below.
        GitChangeBadges.install(context);

        // Auto-refresh timer
        commitRefreshTimer = new Timer(15000, e -> refreshCommitPanel());
        commitRefreshTimer.start();

        // Initial refresh
        refreshCommitPanel();
    }

    public void refreshBranchLabel() {
        if (branchButton == null) return;
        try {
            String branch = rpc.getCurrentBranch(projectName);
            SwingUtilities.invokeLater(() -> branchButton.setText(branch));
        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> branchButton.setText("unknown"));
        }
    }

    public void refreshCommitPanel() {
        if (commitPanel == null) return;
        new Thread(() -> {
            PolicyRefusals.refresh();
            try {
                Dataset ds = rpc.getUncommitedChanges(projectName, userName);
                commitPanel.setChangesData(ds);
                // The Project Browser badges read the same dataset — one poll feeds both, so the
                // marks in the tree can never disagree with the Changes list beside them.
                GitChangeBadges.update(ds);
            } catch (Exception e) {
                // A refusal the gateway words for the user is shown, never left as blank badges;
                // a transient failure (gateway restarting) is not worth one.
                Throwable root = e;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                String msg = root.getMessage();
                if (msg != null && msg.contains(".git folder")) {
                    commitPanel.showError(msg);
                }
            }
        }).start();
    }

    private void cleanupCommitPanel() {
        if (commitRefreshTimer != null) {
            commitRefreshTimer.stop();
            commitRefreshTimer = null;
        }
        if (commitFrameInitialized) {
            try {
                DockingManager dockingManager = context.getDockingManager();
                dockingManager.removeFrame("Commit");
            } catch (Exception ignored) {
            }
            commitFrameInitialized = false;
        }
        commitPanel = null;
        commitFrame = null;
    }

    private void initHistoryPanel() {
        historyPanel = new HistoryPanel();
        GitActionManager.wireHistoryPanel(historyPanel, projectName, userName);

        historyFrame = new DockableFrame("History",
                IconUtils.getIcon("/com/operametrix/ignition/git/icons/ic_history.svg"));
        historyFrame.setTitle(BundleUtil.get().getStringLenient("DesignerHook.History.Title"));
        historyFrame.getContentPane().add(historyPanel);
        historyFrame.setPreferredSize(new Dimension(525, 400));
        historyFrame.setAutohideWidth(525);
        historyFrame.setDockedWidth(525);

        DockingManager dockingManager = context.getDockingManager();

        historyFrame.setInitSide(DockContext.DOCK_SIDE_WEST);
        historyFrame.setInitIndex(0);
        historyFrame.setInitMode(DockContext.STATE_HIDDEN);
        dockingManager.addFrame(historyFrame);
        historyFrameInitialized = true;

        // Initial data load
        refreshHistoryPanel();
    }

    public void refreshHistoryPanel() {
        if (historyPanel == null) return;
        new Thread(() -> {
            try {
                Dataset ds = rpc.getCommitHistory(projectName, 0, HistoryPanel.PAGE_SIZE);
                historyPanel.setData(ds, false);
            } catch (Exception e) {
                // Silently ignore refresh errors
            }
        }).start();
    }

    private void ensurePanelsVisible() {
        if (!commitFrameInitialized && !historyFrameInitialized) return;
        DockingManager dm = context.getDockingManager();
        DockableFrame projectBrowser = dm.getFrame(PROJECT_BROWSER_KEY);

        if (commitFrameInitialized && commitFrame != null) {
            if (dm.getFrame(commitFrame.getKey()) == null) {
                dm.addFrame(commitFrame);
            }
            dm.showFrame(commitFrame.getKey());
            if (projectBrowser != null) {
                dm.moveFrame(commitFrame.getKey(), PROJECT_BROWSER_KEY);
            }
        }
        if (historyFrameInitialized && historyFrame != null) {
            if (dm.getFrame(historyFrame.getKey()) == null) {
                dm.addFrame(historyFrame);
            }
            dm.showFrame(historyFrame.getKey());
            if (projectBrowser != null) {
                dm.moveFrame(historyFrame.getKey(), PROJECT_BROWSER_KEY);
            }
        }
        if (projectBrowser != null) {
            dm.activateFrame(PROJECT_BROWSER_KEY);
        }
    }

    private void cleanupHistoryPanel() {
        if (historyFrameInitialized) {
            try {
                DockingManager dockingManager = context.getDockingManager();
                dockingManager.removeFrame("History");
            } catch (Exception ignored) {
            }
            historyFrameInitialized = false;
        }
        historyPanel = null;
        historyFrame = null;
    }

    @Override
    public void notifyProjectSaveStart(SaveContext save) throws Exception {
        changes = context.getProject().getChanges();
        super.notifyProjectSaveStart(save);
    }

    @Override
    public void notifyProjectSaveDone(){
        super.notifyProjectSaveDone();
        // Saving is when the change list changes, so refresh then rather than leaving it to the
        // 15-second poll. Twice: the first pass catches the common case immediately, and the
        // gateway can still be writing the last resource as this fires, which the follow-up
        // covers. Both are cheap — one RPC that feeds the Changes list and the badges together.
        refreshCommitPanel();
        Timer settle = new Timer(1200, e -> refreshCommitPanel());
        settle.setRepeats(false);
        settle.start();
    }

    @Override
    public void shutdown() {
        super.shutdown();

        StatusBar statusBar = context.getStatusBar();
        if (gitStatusBar != null) {
            statusBar.removeDisplay(gitStatusBar);
        }

        if (gitUserTimer != null) {
            gitUserTimer.stop();
        }

        if (panelVisibilityTimer != null) {
            panelVisibilityTimer.stop();
            panelVisibilityTimer = null;
        }

        GitChangeBadges.uninstall();

        cleanupCommitPanel();
        cleanupHistoryPanel();
    }
}
