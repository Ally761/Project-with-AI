package gameagent.gui;

import java.awt.BorderLayout;

import javax.swing.BorderFactory;
import javax.swing.JPanel;

import gameagent.agent.AgentController;

/** One tab of the window. Every panel works only through the {@link AgentController} facade. */
public abstract class FeaturePanel extends JPanel {
    protected final AgentController agent;
    protected final GuiContext ui;

    protected FeaturePanel(AgentController agent, GuiContext ui) {
        super(new BorderLayout(8, 8));
        this.agent = agent;
        this.ui = ui;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    }

    /** Tab caption. */
    public abstract String title();

    /** Re-reads what the facade knows; called whenever the tab is shown. */
    public void refresh() {}
}
