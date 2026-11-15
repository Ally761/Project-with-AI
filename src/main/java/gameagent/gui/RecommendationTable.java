package gameagent.gui;

import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.table.DefaultTableModel;

import gameagent.model.Recommendation;

/** A list of recommended games with a detail pane and an "open store page" button. Shared by the Recommend and Friends tabs. */
public final class RecommendationTable extends JPanel {
    private final GuiContext ui;
    private final DefaultTableModel model = Widgets.model("#", "Game", "Match", "Kind", "Genres", "Price");
    private final JTable table;
    private final JTextArea detail = Widgets.text("detail", 7, false);
    private final JButton open;
    private List<Recommendation> shown = List.of();

    public RecommendationTable(String name, GuiContext ui) {
        super(new BorderLayout(4, 4));
        this.ui = ui;
        setName(name);
        table = Widgets.table(name + ".table", model);
        table.getColumnModel().getColumn(0).setMaxWidth(36);
        table.getColumnModel().getColumn(2).setMaxWidth(60);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showDetail();
        });
        open = Widgets.button("Open store page", name + ".open", e -> openSelected());
        open.setEnabled(false);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, Widgets.scroll(table, null), Widgets.scroll(detail, "Details"));
        split.setResizeWeight(0.6);
        add(split, BorderLayout.CENTER);
        add(Widgets.flow(open), BorderLayout.SOUTH);
    }

    public void show(List<Recommendation> recs) {
        shown = new ArrayList<>(recs);
        Widgets.clear(model);
        int i = 1;
        for (Recommendation r : shown) {
            var g = r.game();
            model.addRow(new Object[] {i++, g.name() + (g.verified() ? "" : " (unverified)"), r.matchScore() >= 0 ? r.matchScore() + "%" : "",
                    r.label() == null ? "" : r.label(), String.join(", ", g.genres()), g.price()});
        }
        detail.setText(recs.isEmpty() ? "" : "Select a game to see why it was picked.");
        if (!recs.isEmpty()) table.setRowSelectionInterval(0, 0);
        open.setEnabled(!recs.isEmpty() && selected() != null && !selected().game().url().isBlank());
    }

    /** The highlighted recommendation, or null. */
    public Recommendation selected() {
        int row = table.getSelectedRow();
        return row < 0 || row >= shown.size() ? null : shown.get(row);
    }

    public int rowCount() { return shown.size(); }

    private void showDetail() {
        Recommendation r = selected();
        detail.setText(r == null ? "" : Render.recommendationDetail(r));
        detail.setCaretPosition(0);
        open.setEnabled(r != null && !r.game().url().isBlank());
    }

    private void openSelected() {
        Recommendation r = selected();
        if (r != null && !r.game().url().isBlank()) ui.openLink(r.game().url());
    }
}
