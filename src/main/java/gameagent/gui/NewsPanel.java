package gameagent.gui;

import java.awt.BorderLayout;
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
import gameagent.model.NewsItem;

/** Gaming headlines ranked for your tastes. */
public final class NewsPanel extends FeaturePanel {
    private final JTextField topic = Widgets.field("newsTopic", 24);
    private final JSpinner limit = new JSpinner(new SpinnerNumberModel(8, 1, 20, 1));
    private final DefaultTableModel model = Widgets.model("Headline", "Source", "Published", "For you");
    private final JTable table = Widgets.table("newsTable", model);
    private final JTextArea notes = Widgets.text("newsNotes", 3, false);
    private final JLabel info = new JLabel(" ");
    private final JButton fetch, open;
    private List<NewsItem> items = new ArrayList<>();

    public NewsPanel(AgentController agent, GuiContext ui) {
        super(agent, ui);
        limit.setName("newsLimit");
        fetch = Widgets.button("Get news", "fetchNews", e -> fetch());
        open = Widgets.button("Open article", "openArticle", e -> openSelected());
        Widgets.onEnter(topic, this::fetch);
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) openSelected();
            }
        });
        table.getColumnModel().getColumn(0).setPreferredWidth(380);
        table.getColumnModel().getColumn(3).setPreferredWidth(320);

        JPanel top = new JPanel(new BorderLayout());
        top.add(Widgets.flow(new JLabel("Topic (optional)"), topic, new JLabel("How many"), limit, fetch, open), BorderLayout.NORTH);
        top.add(info, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(Widgets.scroll(table, null), BorderLayout.CENTER);
        add(Widgets.scroll(notes, "Notes"), BorderLayout.SOUTH);
    }

    @Override
    public String title() { return "News"; }

    private void fetch() {
        String t = topic.getText().trim();
        int n = (Integer) limit.getValue();
        ui.run("Fetching and ranking news...", () -> agent.news(t, n), r -> {
            items = new ArrayList<>(r.items());
            Widgets.clear(model);
            for (NewsItem x : items) model.addRow(new Object[] {x.title(), x.source(), Render.date(x.published()), x.why()});
            info.setText((r.fromCache() ? "Saved copy from " : "Fetched ") + Render.date(r.fetchedAt())
                    + (r.aiRanked() ? " - ranked for you by the AI" : " - ranked by keyword match (no AI ranking)"));
            notes.setText(String.join("\n", r.warnings()));
            if (!items.isEmpty()) table.setRowSelectionInterval(0, 0);
            ui.status(items.size() + " stories.");
        }, fetch);
    }

    private void openSelected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= items.size()) {
            ui.error("Select a headline first.");
            return;
        }
        ui.openLink(items.get(row).link());
    }
}
