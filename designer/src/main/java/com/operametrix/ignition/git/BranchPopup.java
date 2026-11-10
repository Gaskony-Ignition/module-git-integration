package com.operametrix.ignition.git;

import com.inductiveautomation.ignition.client.icons.VectorIcons;
import com.inductiveautomation.ignition.common.Dataset;
import com.inductiveautomation.ignition.designer.gui.CommonUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class BranchPopup extends JDialog {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    /** One local branch as the gateway classified it. */
    public record Row(String name, String upstream, boolean gone, int ahead, boolean merged,
                      String protectedReason) {
        /** The list text: the name plus what is worth knowing before deleting it. */
        String label(boolean current) {
            StringBuilder sb = new StringBuilder(name);
            if (gone) sb.append("  [gone]");
            if (merged && !current) sb.append("  [merged]");
            if (ahead > 0) sb.append("  \u2191").append(ahead);
            if (protectedReason != null && !"current".equals(protectedReason)) {
                sb.append("  (").append(protectedReason).append(")");
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public static List<Row> rows(Dataset ds) {
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < ds.getRowCount(); i++) {
            out.add(new Row((String) ds.getValueAt(i, "name"), (String) ds.getValueAt(i, "upstream"),
                    Boolean.TRUE.equals(ds.getValueAt(i, "gone")),
                    ((Number) ds.getValueAt(i, "ahead")).intValue(),
                    Boolean.TRUE.equals(ds.getValueAt(i, "merged")),
                    (String) ds.getValueAt(i, "protectedReason")));
        }
        return out;
    }

    private JList<Row> localBranchList;
    private JList<String> remoteBranchList;
    private DefaultListModel<Row> localModel;
    private DefaultListModel<String> remoteModel;
    private String currentBranch = "";
    private final JLabel gatewayWideNote = new JLabel(
            "Branches are gateway-wide in this layout: one checked-out branch for config and every project.");

    public BranchPopup(String currentBranch, List<Row> localBranches, List<String> remoteBranches, Component parent) {
        super(SwingUtilities.getWindowAncestor(parent));
        try {
            InputStream iconStream = getClass().getResourceAsStream("/com/operametrix/ignition/git/icons/ic_branch.svg");
            if (iconStream != null) {
                ImageIcon icon = new ImageIcon(ImageIO.read(iconStream));
                setIconImage(icon.getImage());
            }
        } catch (IOException e) {
            logger.trace(e.toString(), e);
        }

        setTitle("Branch Management");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setContentPane(buildUI());
        setData(currentBranch, localBranches, remoteBranches);

        setSize(600, 420);
        setMinimumSize(new Dimension(500, 350));
        setVisible(true);

        CommonUI.centerComponent(this, parent);
        toFront();
    }

    private JPanel buildUI() {
        JPanel main = new JPanel(new BorderLayout(5, 5));
        main.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Center: branch lists side by side
        JPanel listsPanel = new JPanel(new GridLayout(1, 2, 10, 0));

        localModel = new DefaultListModel<>();
        localBranchList = new JList<>(localModel);
        localBranchList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        localBranchList.setCellRenderer(new CurrentBranchRenderer());
        attachLocalListHandlers();
        JPanel localPanel = new JPanel(new BorderLayout());
        JButton createBtn = buildIconButton(VectorIcons.get("add"),
                PolicyRefusals.label("Create Branch...", "BRANCH_SWITCH"), e -> onCreateBranchRequested());
        JButton localRefreshBtn = buildIconButton(VectorIcons.get("refresh"), "Refresh", e -> onRefresh());
        JButton cleanupBtn = new JButton("Clean up...");
        cleanupBtn.setMargin(new Insets(1, 6, 1, 6));
        cleanupBtn.addActionListener(e -> onCleanupRequested());
        // The popup is built once and reused, so follow the policy rather than reading it here once.
        Runnable applyPolicy = () -> {
            String refused = PolicyRefusals.reason("BRANCH_SWITCH");
            createBtn.setEnabled(refused == null);
            createBtn.setToolTipText(PolicyRefusals.label("Create Branch...", "BRANCH_SWITCH"));
            cleanupBtn.setEnabled(refused == null);
            cleanupBtn.setToolTipText(refused == null
                    ? "Fetch, then delete local branches that are gone from the remote or already merged"
                    : "Clean up (" + refused + ")");
        };
        applyPolicy.run();
        PolicyRefusals.onChange(applyPolicy);
        localPanel.add(buildListHeader("Local Branches", cleanupBtn, createBtn, localRefreshBtn), BorderLayout.NORTH);
        localPanel.add(new JScrollPane(localBranchList), BorderLayout.CENTER);

        remoteModel = new DefaultListModel<>();
        remoteBranchList = new JList<>(remoteModel);
        remoteBranchList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        attachRemoteListHandlers();
        JPanel remotePanel = new JPanel(new BorderLayout());
        JButton remoteRefreshBtn = buildIconButton(VectorIcons.get("refresh"), "Refresh", e -> onRefreshFromRemote());
        remotePanel.add(buildListHeader("Remote Branches", remoteRefreshBtn), BorderLayout.NORTH);
        remotePanel.add(new JScrollPane(remoteBranchList), BorderLayout.CENTER);

        // Mutual exclusivity on selection
        localBranchList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && localBranchList.getSelectedIndex() >= 0) {
                remoteBranchList.clearSelection();
            }
        });
        remoteBranchList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && remoteBranchList.getSelectedIndex() >= 0) {
                localBranchList.clearSelection();
            }
        });

        listsPanel.add(localPanel);
        listsPanel.add(remotePanel);
        main.add(listsPanel, BorderLayout.CENTER);
        gatewayWideNote.setVisible(false);
        main.add(gatewayWideNote, BorderLayout.NORTH);

        return main;
    }

    private void attachLocalListHandlers() {
        localBranchList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    Row selected = localBranchList.getSelectedValue();
                    if (selected != null && !selected.name().equals(currentBranch)) {
                        onCheckoutBranch(selected.name());
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                handlePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handlePopup(e);
            }

            private void handlePopup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int index = localBranchList.locationToIndex(e.getPoint());
                if (index < 0) return;
                localBranchList.setSelectedIndex(index);
                Row row = localModel.getElementAt(index);
                String branch = row.name();

                JPopupMenu menu = new JPopupMenu();

                String policy = PolicyRefusals.reason("BRANCH_SWITCH");
                JMenuItem checkoutItem = new JMenuItem(PolicyRefusals.label("Checkout", "BRANCH_SWITCH"));
                checkoutItem.setEnabled(!branch.equals(currentBranch) && policy == null);
                checkoutItem.addActionListener(a -> onCheckoutBranch(branch));
                menu.add(checkoutItem);

                menu.addSeparator();

                String blocked = branch.equals(currentBranch) ? "current" : row.protectedReason();
                if (blocked == null && policy != null) {
                    blocked = policy;
                }
                JMenuItem deleteItem = new JMenuItem(blocked == null ? "Delete"
                        : "Delete (" + ("current".equals(blocked) ? "current branch"
                        : blocked.equals(policy) ? policy : "protected: " + blocked) + ")");
                deleteItem.setForeground(new Color(0xCC0000));
                deleteItem.setEnabled(blocked == null);
                deleteItem.addActionListener(a -> {
                    int confirm = JOptionPane.showConfirmDialog(BranchPopup.this,
                            "Are you sure you want to delete branch '" + branch + "'?",
                            "Confirm Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (confirm == JOptionPane.YES_OPTION) {
                        onDeleteBranch(branch);
                    }
                });
                menu.add(deleteItem);

                menu.show(localBranchList, e.getX(), e.getY());
            }
        });
    }

    private void attachRemoteListHandlers() {
        remoteBranchList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    // Pass the full remote-tracking name "<remote>/<branch>"; the gateway resolves the
                    // remote and creates/switches the matching local branch.
                    String branch = remoteBranchList.getSelectedValue();
                    if (branch != null && !branch.equals(currentBranch)) {
                        onCheckoutBranch(branch);
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                handlePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handlePopup(e);
            }

            private void handlePopup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int index = remoteBranchList.locationToIndex(e.getPoint());
                if (index < 0) return;
                remoteBranchList.setSelectedIndex(index);
                String branch = remoteModel.getElementAt(index);
                if (branch == null) return;

                JPopupMenu menu = new JPopupMenu();
                JMenuItem checkoutItem = new JMenuItem(PolicyRefusals.label("Checkout", "BRANCH_SWITCH"));
                checkoutItem.setEnabled(!branch.equals(currentBranch) && PolicyRefusals.reason("BRANCH_SWITCH") == null);
                checkoutItem.addActionListener(a -> onCheckoutBranch(branch));
                menu.add(checkoutItem);

                menu.show(remoteBranchList, e.getX(), e.getY());
            }
        });
    }

    private JPanel buildListHeader(String title, AbstractButton... trailingButtons) {
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        header.setBackground(UIManager.getColor("Panel.background"));

        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        header.add(label, BorderLayout.WEST);

        JPanel buttonBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttonBox.setOpaque(false);
        for (AbstractButton btn : trailingButtons) {
            buttonBox.add(btn);
        }
        header.add(buttonBox, BorderLayout.EAST);

        return header;
    }

    private JButton buildIconButton(Icon icon, String tooltip, java.awt.event.ActionListener action) {
        JButton button = new JButton(icon);
        button.setToolTipText(tooltip);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setMargin(new Insets(2, 2, 2, 2));
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
        button.addActionListener(action);
        return button;
    }

    /** Combined layout: the branch is the whole gateway's, not this project's. */
    public void setGatewayWide(boolean gatewayWide) {
        gatewayWideNote.setVisible(gatewayWide);
    }

    public void setData(String currentBranch, List<Row> localBranches, List<String> remoteBranches) {
        this.currentBranch = currentBranch != null ? currentBranch : "";

        localModel.clear();
        for (Row b : localBranches) {
            localModel.addElement(b);
        }

        remoteModel.clear();
        for (String b : remoteBranches) {
            remoteModel.addElement(b);
        }

        localBranchList.repaint();
    }

    /**
     * Renders the current branch with a bold "* " prefix in green.
     */
    private class CurrentBranchRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            Row row = (Row) value;
            String branch = row != null ? row.name() : "";
            setText(row != null ? row.label(branch.equals(currentBranch)) : "");
            if (branch.equals(currentBranch)) {
                setFont(getFont().deriveFont(Font.BOLD));
                if (!isSelected) {
                    setBackground(new Color(0xE8F0FE));
                }
            }
            return this;
        }
    }

    public void onCheckoutBranch(String branchName) {
    }

    /** Called when the user clicks "Create Branch..." — caller should open CreateBranchPopup. */
    public void onCreateBranchRequested() {
    }

    public void onDeleteBranch(String branchName) {
    }

    public void onRefresh() {
    }

    /** Called when the user clicks "Clean up..." — caller fetches, then calls promptCleanup. */
    public void onCleanupRequested() {
    }

    /**
     * Shows gone and merged branches (protected excluded), ticked, for the user to confirm.
     *
     * @return the ticked names; empty if cancelled or nothing ticked
     */
    public List<String> promptCleanup(List<Row> candidates) {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        List<JCheckBox> boxes = new ArrayList<>();
        for (Row r : candidates) {
            JCheckBox cb = new JCheckBox(r.label(false), true);
            cb.setAlignmentX(Component.LEFT_ALIGNMENT);
            boxes.add(cb);
            box.add(cb);
        }
        JScrollPane scroll = new JScrollPane(box);
        scroll.setPreferredSize(new Dimension(420, Math.min(260, 40 + 24 * candidates.size())));
        JPanel body = new JPanel(new BorderLayout(0, 6));
        body.add(new JLabel("Branches gone from the remote or already merged:"), BorderLayout.NORTH);
        body.add(scroll, BorderLayout.CENTER);
        int choice = JOptionPane.showOptionDialog(this, body, "Clean Up Branches",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE, null,
                new Object[]{"Delete selected", "Cancel"}, "Cancel");
        List<String> picked = new ArrayList<>();
        if (choice == 0) {
            for (int i = 0; i < boxes.size(); i++) {
                if (boxes.get(i).isSelected()) picked.add(candidates.get(i).name());
            }
        }
        return picked;
    }

    public List<Row> localRows() {
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < localModel.size(); i++) out.add(localModel.get(i));
        return out;
    }

    public String getCurrentBranch() {
        return currentBranch;
    }

    /** Called when the user clicks the Refresh button — fetches from remote first. */
    public void onRefreshFromRemote() {
    }
}
