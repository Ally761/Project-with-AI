package gameagent.gui;

import java.awt.BorderLayout;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import gameagent.agent.AgentController;

/** Free conversation with the agent: it remembers preferences while you talk and uses its tools to answer. */
public final class ChatPanel extends FeaturePanel {
    private final JTextArea transcript = Widgets.text("transcript", 20, false);
    private final JTextField input = Widgets.field("chatInput", 40);
    private final JButton send;

    public ChatPanel(AgentController agent, GuiContext ui) {
        super(agent, ui);
        send = Widgets.button("Send", "chatSend", e -> send());
        Widgets.onEnter(input, this::send);
        JPanel bottom = new JPanel(new BorderLayout(6, 0));
        bottom.add(input, BorderLayout.CENTER);
        bottom.add(send, BorderLayout.EAST);
        add(Widgets.scroll(transcript, null), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        append(agent.profile().isEmpty()
                ? "Agent: I don't know your tastes yet. Tell me about games you love or hate, or ask for a recommendation."
                : "Agent: Welcome back! Ask for a recommendation, news or a playtime, or tell me what you're in the mood for.");
    }

    @Override
    public String title() { return "Chat"; }

    private void send() {
        String text = input.getText().trim();
        if (text.isEmpty()) return;
        input.setText("");
        append("\nYou: " + text);
        ui.run("Thinking...", () -> agent.chat(text), reply -> {
            var c = reply.context();
            if (!c.profileChanges.isEmpty()) {
                StringBuilder s = new StringBuilder();
                c.profileChanges.forEach(x -> s.append("  (remembered) ").append(x).append("\n"));
                append("\n" + s.toString().stripTrailing());
            }
            append("\nAgent: " + reply.text());
            if (!c.recommendations.isEmpty()) append("\n" + Render.recommendations(c.recommendations).stripTrailing());
            if (!c.news.isEmpty()) append("\n" + Render.news(c.news).stripTrailing());
            if (c.playtime != null) append("\n" + Render.playtime(c.playtime).stripTrailing());
            if (c.calendar != null) append("\n" + Render.calendar(c.calendar).stripTrailing());
            c.warnings.forEach(w -> append("Note: " + w));
        }, send, input);
    }

    private void append(String s) {
        transcript.append(s + "\n");
        transcript.setCaretPosition(transcript.getDocument().getLength());
    }
}
