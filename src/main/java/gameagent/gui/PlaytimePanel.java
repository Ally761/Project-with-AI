package gameagent.gui;

import java.awt.BorderLayout;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import gameagent.agent.AgentController;

/** How long a game takes you: typical time (community data, AI fallback) scaled to your own pace. */
public final class PlaytimePanel extends FeaturePanel {
    private final JTextField game = Widgets.field("playtimeGame", 30);
    private final JTextArea result = Widgets.text("playtimeResult", 14, true);
    private final JButton estimate;

    public PlaytimePanel(AgentController agent, GuiContext ui) {
        super(agent, ui);
        estimate = Widgets.button("How long will it take me?", "estimate", e -> estimate());
        Widgets.onEnter(game, this::estimate);
        add(Widgets.flow(new JLabel("Game"), game, estimate), BorderLayout.NORTH);
        add(Widgets.scroll(result, "Estimate"), BorderLayout.CENTER);
        result.setText("Type a game's name. The typical time comes from IGDB community data when configured (otherwise an AI estimate); "
                + "if your linked Steam history shows your pace, it is adjusted to you.");
    }

    @Override
    public String title() { return "Playtime"; }

    private void estimate() {
        String g = game.getText().trim();
        if (g.isEmpty()) {
            ui.error("Enter a game's name first.");
            return;
        }
        ui.run("Estimating...", () -> agent.estimatePlaytime(g), r -> {
            result.setText(Render.playtime(r));
            result.setCaretPosition(0);
        }, estimate);
    }
}
