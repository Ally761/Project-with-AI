package gameagent.gui;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;

import gameagent.agent.AgentController;
import gameagent.model.ApplyResult;
import gameagent.model.LogEntry;
import gameagent.profile.Category;
import gameagent.profile.ConflictResolver;
import gameagent.profile.Preference;
import gameagent.profile.ProfileUpdate;
import gameagent.profile.Sentiment;
import gameagent.profile.UserProfile;

/** What the agent knows about you: weighted likes and dislikes, notes and the interest log. */
public final class ProfilePanel extends FeaturePanel {
    private final DefaultTableModel likes = Widgets.model("Type", "Name", "Weight");
    private final DefaultTableModel dislikes = Widgets.model("Type", "Name", "Weight");
    private final JTable likeTable = Widgets.table("likes", likes);
    private final JTable dislikeTable = Widgets.table("dislikes", dislikes);
    private final List<Preference> likeRows = new ArrayList<>();
    private final List<Preference> dislikeRows = new ArrayList<>();
    private final DefaultListModel<String> noteModel = new DefaultListModel<>();
    private final JList<String> noteList = new JList<>(noteModel);
    private final JTextArea log = Widgets.text("log", 8, true);
    private final JComboBox<String> category = new JComboBox<>(new String[] {"genre", "tag", "game", "platform"});
    private final JTextField name = Widgets.field("prefName", 18);
    private final JTextField note = Widgets.field("noteText", 28);

    public ProfilePanel(AgentController agent, GuiContext ui) {
        super(agent, ui);
        category.setName("prefCategory");
        noteList.setName("notes");
        noteList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        likeTable.getSelectionModel().addListSelectionListener(e -> { if (likeTable.getSelectedRow() >= 0) dislikeTable.clearSelection(); });
        dislikeTable.getSelectionModel().addListSelectionListener(e -> { if (dislikeTable.getSelectedRow() >= 0) likeTable.clearSelection(); });

        JPanel form = Widgets.flow(new JLabel("Add:"), category, name,
                Widgets.button("I like it", "like", e -> add(true)),
                Widgets.button("I dislike it", "dislike", e -> add(false)),
                Widgets.button("Remove selected", "removePref", e -> removeSelected()),
                Widgets.button("Reset profile...", "reset", e -> reset()));
        Widgets.onEnter(name, () -> add(true));
        add(form, BorderLayout.NORTH);

        JPanel notesPanel = new JPanel(new BorderLayout(4, 4));
        notesPanel.add(Widgets.scroll(noteList, null), BorderLayout.CENTER);
        notesPanel.add(Widgets.flow(note, Widgets.button("Add note", "addNote", e -> addNote()),
                Widgets.button("Remove note", "removeNote", e -> removeNote())), BorderLayout.SOUTH);
        notesPanel.setBorder(javax.swing.BorderFactory.createTitledBorder("Notes (things to remember, e.g. \"I only have 45 minutes a night\")"));
        Widgets.onEnter(note, this::addNote);

        JPanel grid = new JPanel(new GridLayout(2, 2, 8, 8));
        grid.add(Widgets.scroll(likeTable, "Likes (higher weight = stronger)"));
        grid.add(Widgets.scroll(dislikeTable, "Dislikes"));
        grid.add(notesPanel);
        grid.add(Widgets.scroll(log, "Interest log (latest last)"));
        add(grid, BorderLayout.CENTER);
        refresh();
    }

    @Override
    public String title() { return "Profile"; }

    @Override
    public void refresh() {
        UserProfile p = agent.profile();
        fill(likes, likeRows, p, Sentiment.LIKE);
        fill(dislikes, dislikeRows, p, Sentiment.DISLIKE);
        noteModel.clear();
        p.notes().forEach(noteModel::addElement);
        List<LogEntry> entries = p.log();
        StringBuilder s = new StringBuilder();
        for (LogEntry e : entries.subList(Math.max(0, entries.size() - 30), entries.size())) {
            String text = e.text().length() > 110 ? e.text().substring(0, 107) + "..." : e.text();
            s.append(Render.date(e.time())).append("  [").append(e.type()).append("] ").append(text).append("\n");
        }
        log.setText(s.length() == 0 ? "Nothing logged yet." : s.toString());
        log.setCaretPosition(log.getDocument().getLength());
    }

    private static void fill(DefaultTableModel model, List<Preference> rows, UserProfile p, Sentiment s) {
        Widgets.clear(model);
        rows.clear();
        List<Preference> sorted = new ArrayList<>(p.preferences().stream().filter(x -> x.sentiment() == s).toList());
        sorted.sort((a, b) -> Double.compare(b.weight(), a.weight()));
        for (Preference pr : sorted) {
            rows.add(pr);
            model.addRow(new Object[] {pr.category().name().toLowerCase(), pr.name(), String.format("%.1f", pr.weight())});
        }
    }

    private void add(boolean like) {
        String n = name.getText().trim();
        if (n.isEmpty()) {
            ui.error("Type a genre, tag, game or platform first.");
            return;
        }
        ProfileUpdate u = new ProfileUpdate("user");
        Category c = Category.parse((String) category.getSelectedItem());
        if (like) u.like(c, n); else u.dislike(c, n);
        ConflictResolver ask = (existing, incoming) -> ui.confirm("You currently "
                + (existing.sentiment() == Sentiment.LIKE ? "like" : "dislike") + " \"" + existing.name() + "\". Switch to "
                + (incoming == Sentiment.LIKE ? "liking" : "disliking") + " it?")
                ? ConflictResolver.Resolution.USE_NEW : ConflictResolver.Resolution.KEEP_EXISTING;
        report(agent.updateProfile(u, ask));
        name.setText("");
        refresh();
    }

    private void removeSelected() {
        Preference p = null;
        int r = likeTable.getSelectedRow();
        if (r >= 0 && r < likeRows.size()) p = likeRows.get(r);
        r = dislikeTable.getSelectedRow();
        if (p == null && r >= 0 && r < dislikeRows.size()) p = dislikeRows.get(r);
        if (p == null) {
            ui.error("Select a like or dislike in one of the lists first.");
            return;
        }
        report(agent.updateProfile(new ProfileUpdate("user").remove(p.category(), p.name()), ConflictResolver.KEEP_EXISTING));
        refresh();
    }

    private void addNote() {
        String t = note.getText().trim();
        if (t.isEmpty()) return;
        report(agent.updateProfile(new ProfileUpdate("user").note(t), ConflictResolver.NEWEST_WINS));
        note.setText("");
        refresh();
    }

    private void removeNote() {
        int i = noteList.getSelectedIndex();
        if (i < 0) {
            ui.error("Select a note first.");
            return;
        }
        agent.removeNote(i + 1);
        refresh();
    }

    private void reset() {
        if (!ui.confirm("Erase your whole profile, saved catalogue and chat memory? This cannot be undone.")) return;
        agent.resetProfile();
        refresh();
        ui.status("Profile cleared.");
    }

    private void report(ApplyResult r) {
        List<String> all = new ArrayList<>();
        r.applied().forEach(s -> all.add("+ " + s));
        r.skipped().forEach(s -> all.add("- " + s));
        ui.status(all.isEmpty() ? "Nothing changed." : String.join("   ", all));
    }
}
