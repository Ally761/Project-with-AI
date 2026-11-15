package gameagent.gui;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;

import gameagent.agent.AgentController;
import gameagent.model.GameFeedback;
import gameagent.model.RefinementAnswer;

/** Rate games you played, and take the short interview that fills the gaps in your profile. */
public final class FeedbackPanel extends FeaturePanel {
    private final JTextField game = Widgets.field("feedbackGame", 24);
    private final JComboBox<Integer> rating = new JComboBox<>(new Integer[] {5, 4, 3, 2, 1});
    private final JTextField comment = Widgets.field("feedbackComment", 40);
    private final JTextArea result = Widgets.text("feedbackResult", 5, false);
    private final DefaultTableModel history = Widgets.model("Game", "Rating", "Comment", "When");
    private final JButton submit, refine;

    public FeedbackPanel(AgentController agent, GuiContext ui) {
        super(agent, ui);
        rating.setName("feedbackRating");
        submit = Widgets.button("Save feedback", "submitFeedback", e -> submit());
        refine = Widgets.button("Start the interview", "refine", e -> refine());
        Widgets.onEnter(comment, this::submit);

        JPanel form = new JPanel(new GridLayout(0, 1, 2, 2));
        form.setBorder(javax.swing.BorderFactory.createTitledBorder("Rate a game you played (your comment is read by the AI)"));
        form.add(Widgets.flow(new JLabel("Game"), game, new JLabel("Rating (5 = loved it)"), rating));
        form.add(Widgets.flow(new JLabel("Comment"), comment, submit));

        JPanel interview = Widgets.flow(refine, new JLabel("A few questions from the AI to fill gaps in your profile. Leave any blank to skip."));
        interview.setBorder(javax.swing.BorderFactory.createTitledBorder("Preference interview"));

        JPanel top = new JPanel(new BorderLayout());
        top.add(form, BorderLayout.NORTH);
        top.add(interview, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        JPanel center = new JPanel(new GridLayout(2, 1, 6, 6));
        center.add(Widgets.scroll(result, "What I learned"));
        center.add(Widgets.scroll(Widgets.table("feedbackHistory", history), "Your ratings"));
        add(center, BorderLayout.CENTER);
        refresh();
    }

    @Override
    public String title() { return "Feedback"; }

    @Override
    public void refresh() {
        Widgets.clear(history);
        List<GameFeedback> all = new ArrayList<>(agent.profile().feedback());
        for (int i = all.size() - 1; i >= 0; i--) {
            GameFeedback f = all.get(i);
            history.addRow(new Object[] {f.gameName(), f.rating() + "/5", f.comment(), Render.date(f.when())});
        }
    }

    private void submit() {
        String g = game.getText().trim();
        if (g.isEmpty()) {
            ui.error("Enter the game's name.");
            return;
        }
        int r = (Integer) rating.getSelectedItem();
        String c = comment.getText();
        ui.run("Saving your feedback...", () -> agent.submitFeedback(g, r, c), res -> {
            StringBuilder s = new StringBuilder("Saved your " + r + "/5 for " + g + ".\n");
            res.changes().forEach(x -> s.append("  + ").append(x).append("\n"));
            res.warnings().forEach(x -> s.append("  Note: ").append(x).append("\n"));
            result.setText(s.toString());
            game.setText("");
            comment.setText("");
            refresh();
        }, submit);
    }

    private void refine() {
        ui.run("Thinking of questions...", () -> agent.refinementQuestions(4), questions -> {
            if (questions.isEmpty()) {
                ui.info("Your profile already covers the basics. Use the Profile tab or chat to fine-tune it.");
                return;
            }
            List<String> answers = ui.askAll("A few quick questions (leave blank to skip)", questions);
            if (answers == null) return;
            List<RefinementAnswer> given = new ArrayList<>();
            for (int i = 0; i < questions.size() && i < answers.size(); i++) {
                if (!answers.get(i).isBlank()) given.add(new RefinementAnswer(questions.get(i), answers.get(i)));
            }
            if (given.isEmpty()) {
                ui.status("No answers given, nothing changed.");
                return;
            }
            ui.run("Updating your profile...", () -> agent.submitRefinement(given), r -> {
                StringBuilder s = new StringBuilder();
                if (r.applied().isEmpty()) s.append("Nothing new learned.\n");
                r.applied().forEach(x -> s.append("  + ").append(x).append("\n"));
                r.skipped().forEach(x -> s.append("  - ").append(x).append("\n"));
                result.setText(s.toString());
            }, refine);
        }, refine);
    }
}
