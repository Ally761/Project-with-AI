package gameagent.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.JTabbedPane;

import gameagent.agent.AgentController;

/** The main window: one tab per feature, all backed by the same {@link AgentController} the command line uses. */
public final class GuiApp {
    private final JFrame frame = new JFrame("GameAgent - AI game recommendation assistant");
    private final SwingContext context = new SwingContext(frame);

    public GuiApp(AgentController agent) {
        List<FeaturePanel> panels = panels(agent, context);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(tabs(panels), BorderLayout.CENTER);
        frame.add(context.statusBar(), BorderLayout.SOUTH);
        frame.setPreferredSize(new Dimension(1100, 760));
        frame.pack();
        frame.setLocationRelativeTo(null);
        context.status("Model: " + agent.modelName());
        if ("unavailable".equals(agent.modelName())) {
            context.status("No LLM API key is set, so AI features will report an error. Copy .env.example to .env and set LLM_API_KEY (see README).");
        }
    }

    /** The tabs, in display order. Public so tests can build the same screens with a test double for the window. */
    public static List<FeaturePanel> panels(AgentController agent, GuiContext ui) {
        return List.of(new ChatPanel(agent, ui), new RecommendPanel(agent, ui), new ProfilePanel(agent, ui),
                new FeedbackPanel(agent, ui), new NewsPanel(agent, ui), new SteamPanel(agent, ui),
                new CalendarPanel(agent, ui), new PlaytimePanel(agent, ui), new FriendsPanel(agent, ui));
    }

    public static JTabbedPane tabs(List<FeaturePanel> panels) {
        JTabbedPane tabs = new JTabbedPane();
        for (FeaturePanel p : panels) tabs.addTab(p.title(), p);
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedComponent() instanceof FeaturePanel fp) fp.refresh();
        });
        return tabs;
    }

    public void show() { frame.setVisible(true); }

    /** Safe to call from any thread (retry notices arrive from background work). */
    public void status(String text) { context.status(text); }
}
