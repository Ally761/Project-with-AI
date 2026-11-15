package gameagent.gui;

import java.awt.BorderLayout;
import java.util.Optional;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;

import gameagent.agent.AgentController;
import gameagent.model.OwnedGame;
import gameagent.model.SteamLink;
import gameagent.model.SteamLinkResult;
import gameagent.profile.UserProfile;

/** Link a public Steam profile so the agent learns from what you play. No login or API key is used. */
public final class SteamPanel extends FeaturePanel {
    private final JTextField profile = Widgets.field("steamProfile", 36);
    private final JLabel info = new JLabel(" ");
    private final DefaultTableModel model = Widgets.model("#", "Game", "Hours", "Last 2 weeks");
    private final JTextArea result = Widgets.text("steamResult", 5, false);
    private final JButton link, refreshButton, unlink;

    public SteamPanel(AgentController agent, GuiContext ui) {
        super(agent, ui);
        link = Widgets.button("Link", "steamLink", e -> link());
        refreshButton = Widgets.button("Refresh", "steamRefresh", e -> refreshSteam());
        unlink = Widgets.button("Unlink", "steamUnlink", e -> unlink());
        Widgets.onEnter(profile, this::link);

        JPanel top = new JPanel(new BorderLayout());
        top.add(Widgets.flow(new JLabel("Public profile link"), profile, link, refreshButton, unlink), BorderLayout.NORTH);
        top.add(info, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(Widgets.scroll(Widgets.table("steamGames", model), "Most-played games (Steam shows only these to anyone not signed in)"), BorderLayout.CENTER);
        add(Widgets.scroll(result, "What was added to your profile"), BorderLayout.SOUTH);
        refresh();
    }

    @Override
    public String title() { return "Steam"; }

    @Override
    public void refresh() {
        UserProfile p = agent.profile();
        Optional<SteamLink> l = p.steamLink();
        Widgets.clear(model);
        if (l.isEmpty()) {
            info.setText("No Steam profile linked. Paste your link, e.g. https://steamcommunity.com/id/yourname "
                    + "(set \"My profile\" and \"Game details\" to Public in Steam's privacy settings).");
            return;
        }
        double hours = p.ownedGames().stream().mapToDouble(OwnedGame::hours).sum();
        info.setText("Linked to " + l.get().personaName() + ": " + p.ownedGames().size() + " most-played games, "
                + Math.round(hours) + " hours (synced " + l.get().linkedAt().toString().substring(0, 10) + ")");
        int rank = 1;
        for (OwnedGame g : p.mostPlayed(15)) {
            model.addRow(new Object[] {rank++, g.name(), Math.round(g.hours()), Math.round(g.hoursRecent())});
        }
    }

    private void link() {
        String text = profile.getText().trim();
        if (text.isEmpty()) {
            ui.error("Paste your public Steam profile link first.");
            return;
        }
        ui.run("Reading your Steam profile...", () -> agent.linkSteam(text), this::show, link, refreshButton, unlink);
    }

    private void refreshSteam() {
        ui.run("Re-reading your Steam profile...", agent::refreshSteam, this::show, link, refreshButton, unlink);
    }

    private void show(SteamLinkResult r) {
        StringBuilder s = new StringBuilder("Linked " + r.link().personaName() + ": read " + r.libraryCount()
                + (r.libraryCount() == 1 ? " game, " : " games, ") + Math.round(r.totalHours()) + " hours between them.\n");
        if (r.applied().isEmpty()) s.append("No new tastes to add.\n");
        else {
            s.append("Added to your profile from your playtime:\n");
            r.applied().forEach(x -> s.append("  - ").append(x).append("\n"));
        }
        r.warnings().forEach(w -> s.append("Note: ").append(w).append("\n"));
        result.setText(s.toString());
        profile.setText("");
        refresh();
        ui.status("Steam profile linked.");
    }

    private void unlink() {
        Optional<SteamLink> l = agent.profile().steamLink();
        if (l.isEmpty()) {
            ui.error("No Steam profile is linked.");
            return;
        }
        if (!ui.confirm("Unlink " + l.get().personaName() + " and forget the library? Owned games may be recommended again.")) return;
        boolean remove = ui.confirm("Also remove the likes that were imported from Steam? (things you said yourself are kept)");
        var removed = agent.unlinkSteam(remove);
        result.setText("Steam profile unlinked." + (removed.isEmpty() ? "" : " Removed " + removed.size() + " imported preferences."));
        refresh();
        ui.status("Steam profile unlinked.");
    }
}
