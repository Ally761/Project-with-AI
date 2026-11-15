package gameagent.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Locale;

import gameagent.agent.AgentController;

/** Everything a command needs: the facade, console input/output and small prompt helpers. */
public class CliContext {
    private final AgentController agent;
    private final BufferedReader in;
    private final PrintStream out;
    private CommandRegistry registry;

    public CliContext(AgentController agent, BufferedReader in, PrintStream out) {
        this.agent = agent;
        this.in = in;
        this.out = out;
    }

    public AgentController agent() { return agent; }
    public PrintStream out() { return out; }
    public CommandRegistry registry() { return registry; }
    void setRegistry(CommandRegistry r) { this.registry = r; }

    /** Prompts and reads a line; returns null at end of input. */
    public String ask(String prompt) {
        out.print(prompt);
        out.flush();
        try {
            return in.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    public boolean confirm(String question) {
        String a = ask(question + " [y/N] ");
        return a != null && a.trim().toLowerCase(Locale.ROOT).startsWith("y");
    }

    public void println(String s) { out.println(s); }
}
