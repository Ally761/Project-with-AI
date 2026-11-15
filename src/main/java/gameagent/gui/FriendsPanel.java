package gameagent.gui;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.DefaultTableModel;

import gameagent.agent.AgentController;
import gameagent.friends.Friend;
import gameagent.model.OwnedGame;

/** Add friends by their public Steam profile link and get games they play that suit you. */
public final class FriendsPanel extends FeaturePanel {
    private final JTextField link = Widgets.field("friendLink", 36);
    private final DefaultTableModel model = Widgets.model("Friend", "Games known", "Updated", "Profile");
    private final JTable table = Widgets.table("friendsTable", model);
    private final JTextArea common = Widgets.text("commonGames", 6, false);
    private final JSpinner count = new JSpinner(new SpinnerNumberModel(5, 1, 10, 1));
    private final RecommendationTable results;
    private final JButton add, remove, refreshAll, commonButton, recSelected, recAll;
    private List<Friend> friends = new ArrayList<>();

    public FriendsPanel(AgentController agent, GuiContext ui) {
        super(agent, ui);
        count.setName("friendCount");
        results = new RecommendationTable("friendResults", ui);
        add = Widgets.button("Add friend", "addFriend", e -> addFriend());
        remove = Widgets.button("Remove selected", "removeFriend", e -> removeFriend());
        refreshAll = Widgets.button("Re-read all", "refreshFriends", e -> refreshFriends());
        commonButton = Widgets.button("Games in common", "common", e -> common());
        recSelected = Widgets.button("Recommend from selected", "recommendSelected", e -> recommend(true));
        recAll = Widgets.button("Recommend from all friends", "recommendAll", e -> recommend(false));
        Widgets.onEnter(link, this::addFriend);

        JPanel top = new JPanel(new BorderLayout());
        top.add(Widgets.flow(new JLabel("Friend's public profile link"), link, add, remove, refreshAll), BorderLayout.NORTH);
        JPanel list = new JPanel(new GridLayout(1, 2, 8, 8));
        list.add(Widgets.scroll(table, "Friends (Steam shows only each profile's most-played games)"));
        list.add(Widgets.scroll(common, "What you and the selected friend play"));
        top.add(list, BorderLayout.CENTER);
        top.setPreferredSize(new java.awt.Dimension(900, 230));

        JPanel recs = new JPanel(new BorderLayout(4, 4));
        recs.add(Widgets.flow(commonButton, new JLabel("How many"), count, recSelected, recAll), BorderLayout.NORTH);
        recs.add(results, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);
        add(recs, BorderLayout.CENTER);
        refresh();
    }

    @Override
    public String title() { return "Friends"; }

    @Override
    public void refresh() {
        friends = new ArrayList<>(agent.friends());
        Widgets.clear(model);
        for (Friend f : friends) {
            model.addRow(new Object[] {f.name(), f.games().size(), Render.date(f.updated()), f.profileUrl()});
        }
    }

    private String selectedName() {
        int row = table.getSelectedRow();
        return row < 0 || row >= friends.size() ? null : friends.get(row).name();
    }

    private void addFriend() {
        String text = link.getText().trim();
        if (text.isEmpty()) {
            ui.error("Paste your friend's public Steam profile link first.");
            return;
        }
        ui.run("Reading your friend's profile...", () -> agent.addFriend(text), f -> {
            link.setText("");
            refresh();
            ui.status("Added " + f.name() + " (" + f.games().size() + " games known).");
        }, add);
    }

    private void removeFriend() {
        String n = selectedName();
        if (n == null) {
            ui.error("Select a friend first.");
            return;
        }
        agent.removeFriend(n);
        refresh();
        ui.status("Removed " + n + ".");
    }

    private void refreshFriends() {
        ui.run("Re-reading your friends...", agent::refreshFriends, problems -> {
            refresh();
            if (problems.isEmpty()) ui.status("Friends updated.");
            else ui.info(String.join("\n", problems));
        }, refreshAll);
    }

    private void common() {
        String n = selectedName();
        if (n == null) {
            ui.error("Select a friend first.");
            return;
        }
        ui.run("Comparing libraries...", () -> agent.commonGames(n), c -> {
            StringBuilder s = new StringBuilder();
            s.append("You both play: ").append(c.shared().isEmpty() ? "(nothing found)" : String.join(", ", c.shared())).append("\n\n");
            s.append(c.friend()).append(" plays and you don't: ");
            s.append(c.onlyTheirs().isEmpty() ? "(nothing found)" : String.join(", ", c.onlyTheirs().stream().map(OwnedGame::name).toList()));
            if (!c.complete()) s.append("\n\nNote: only most-played games are visible, so this list is incomplete.");
            common.setText(s.toString());
        }, commonButton);
    }

    private void recommend(boolean selectedOnly) {
        String n = selectedOnly ? selectedName() : "";
        if (selectedOnly && n == null) {
            ui.error("Select a friend first, or use \"Recommend from all friends\".");
            return;
        }
        int c = (Integer) count.getValue();
        ui.run("Finding games your friends play...", () -> agent.recommendFromFriends(n, c), r -> {
            results.show(r.recommendations());
            ui.status(r.warnings().isEmpty() ? r.recommendations().size() + " picks." : String.join("   ", r.warnings()));
        }, recSelected, recAll);
    }
}
