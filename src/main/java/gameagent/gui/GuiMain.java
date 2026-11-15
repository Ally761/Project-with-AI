package gameagent.gui;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import gameagent.AppContext;
import gameagent.agent.AgentController;
import gameagent.config.AppConfig;

/** Entry point for the window version. Same configuration, data folder and agent as the command line. */
public final class GuiMain {
    private GuiMain() {}

    public static void main(String[] args) throws Exception {
        AtomicReference<GuiApp> app = new AtomicReference<>();
        AppConfig config = AppConfig.load(Path.of(".env"));
        AgentController agent = AppContext.fromConfig(config, message -> {
            GuiApp a = app.get();
            if (a != null) a.status(message); else System.err.println(message);
        });
        SwingUtilities.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // the default look is fine
            }
            GuiApp g = new GuiApp(agent);
            app.set(g);
            g.show();
        });
    }
}
