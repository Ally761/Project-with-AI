package gameagent.gui;

import java.awt.BorderLayout;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

import gameagent.agent.AgentController;
import gameagent.model.Recommendation;

/** Personalised recommendations, the curveball roulette and the saved catalogue. */
public final class RecommendPanel extends FeaturePanel {
    private final JTextField query = Widgets.field("query", 30);
    private final JSpinner count = new JSpinner(new SpinnerNumberModel(5, 1, 10, 1));
    private final RecommendationTable results;
    private final JTextArea notes = Widgets.text("notes", 2, false);
    private final JLabel hint = new JLabel(" ");
    private final JButton recommend, curveball, catalog, rate;

    public RecommendPanel(AgentController agent, GuiContext ui) {
        super(agent, ui);
        count.setName("count");
        results = new RecommendationTable("results", ui);
        recommend = Widgets.button("Recommend", "recommend", e -> recommend());
        curveball = Widgets.button("Curveball roulette", "curveball", e -> curveball());
        catalog = Widgets.button("My catalogue", "catalog", e -> showCatalog());
        rate = Widgets.button("Rate selected game...", "rate", e -> rateSelected());
        Widgets.onEnter(query, this::recommend);

        JPanel top = new JPanel(new BorderLayout());
        top.add(Widgets.flow(new JLabel("What are you in the mood for?"), query, new JLabel("How many"), count, recommend, curveball, catalog),
                BorderLayout.NORTH);
        top.add(hint, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(results, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(Widgets.scroll(notes, "Notes"), BorderLayout.CENTER);
        bottom.add(Widgets.flow(rate), BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
    }

    @Override
    public String title() { return "Recommend"; }

    @Override
    public void refresh() {
        hint.setText(agent.needsMoreInfo()
                ? "Tip: I know little about your tastes yet. Chat with me, add likes on the Profile tab or take the interview on the Feedback tab."
                : " ");
    }

    private int n() { return (Integer) count.getValue(); }

    private void recommend() {
        String q = query.getText().trim();
        ui.run("Finding games for you...", () -> agent.recommend(q, n()), r -> {
            results.show(r.recommendations());
            notes.setText(String.join("\n", r.warnings()));
            ui.status(r.recommendations().isEmpty() ? "No recommendations came back." : r.recommendations().size() + " recommendations saved to your catalogue.");
        }, recommend, curveball, catalog);
    }

    private void curveball() {
        String hintText = query.getText().trim();
        ui.run("Rolling the curveball...", () -> agent.curveball(hintText), r -> {
            results.show(r.recommendations());
            notes.setText(String.join("\n", r.warnings()));
            ui.status("One popular pick and one hidden gem.");
        }, recommend, curveball, catalog);
    }

    private void showCatalog() {
        List<Recommendation> all = agent.catalog().all();
        results.show(all);
        notes.setText(agent.catalog().isStale() && !all.isEmpty()
                ? "Your preferences changed since this catalogue was built. Press Recommend to refresh it." : "");
        ui.status(all.isEmpty() ? "Your catalogue is empty. Press Recommend first." : all.size() + " games in your catalogue.");
    }

    private void rateSelected() {
        Recommendation r = results.selected();
        if (r == null) {
            ui.error("Select a game in the list first.");
            return;
        }
        String game = r.game().name();
        List<String> a = ui.askAll("Rate " + game, List.of("Rating (1-5)", "Comment (optional)"));
        if (a == null) return;
        int rating;
        try {
            rating = Integer.parseInt(a.get(0).trim());
        } catch (NumberFormatException e) {
            ui.error("The rating must be a number from 1 to 5.");
            return;
        }
        ui.run("Saving your rating...", () -> agent.submitFeedback(game, rating, a.get(1)), res -> {
            StringBuilder s = new StringBuilder("Saved your " + rating + "/5 for " + game + ".");
            res.changes().forEach(c -> s.append("\n  + ").append(c));
            res.warnings().forEach(w -> s.append("\n  Note: ").append(w));
            ui.info(s.toString());
        }, rate);
    }
}
