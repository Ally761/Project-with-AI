package gameagent.cli;

import java.io.BufferedReader;
import java.io.PrintStream;

import gameagent.agent.AgentController;
import gameagent.agent.AgentReply;
import gameagent.cli.commands.Commands;
import gameagent.llm.LLMException;
import gameagent.news.NewsException;
import gameagent.recommend.RecommendationException;

/** The interactive console: slash commands are dispatched to {@link Command}s, anything else goes to the chat agent. */
public class CliApp {
    private final CliContext ctx;
    private final CommandRegistry registry;
    private final AgentController agent;

    public CliApp(AgentController agent, BufferedReader in, PrintStream out) {
        this.agent = agent;
        this.ctx = new CliContext(agent, in, out);
        this.registry = Commands.standard();
        ctx.setRegistry(registry);
    }

    public void run() {
        ctx.println("=== GameAgent: your AI game recommendation assistant ===");
        ctx.println("Model: " + agent.modelName());
        if (agent.modelName().equals("unavailable")) {
            ctx.println("! No LLM API key is configured, so AI features will report an error.");
            ctx.println("  Copy .env.example to .env and set LLM_API_KEY (see README). Profile commands still work.");
        }
        ctx.println("Chat naturally, or type /help for commands. Type /quit to leave.");
        ctx.println(agent.profile().isEmpty()
                ? "I don't know your tastes yet - tell me about games you love or hate!"
                : "Welcome back! Use /recommend, /news or /curveball, or just tell me what you're in the mood for.");

        while (true) {
            String line = ctx.ask("\nyou> ");
            if (line == null) break;
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                if (line.startsWith("/")) {
                    dispatch(line);
                } else {
                    chat(line);
                }
            } catch (QuitException q) {
                break;
            } catch (LLMException e) {
                ctx.println("! AI service problem: " + e.getMessage());
            } catch (RecommendationException | NewsException e) {
                ctx.println("! " + e.getMessage());
            } catch (Exception e) {
                ctx.println("! Something went wrong: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        ctx.println("Goodbye! Your profile has been saved.");
    }

    private void dispatch(String line) throws Exception {
        String[] parts = line.substring(1).split("\\s+", 2);
        String name = parts[0];
        String args = parts.length > 1 ? parts[1] : "";
        Command cmd = registry.find(name).orElse(null);
        if (cmd == null) {
            ctx.println("Unknown command /" + name + ". Type /help.");
            return;
        }
        cmd.execute(args, ctx);
    }

    private void chat(String text) throws LLMException {
        ctx.println("thinking...");
        AgentReply reply = agent.chat(text);
        var c = reply.context();
        if (!c.profileChanges.isEmpty()) {
            ctx.println("");
            c.profileChanges.forEach(s -> ctx.println("  (remembered) " + s));
        }
        ctx.println("\nagent> " + reply.text());
        if (!c.recommendations.isEmpty()) CliSupport.showRecommendations(ctx, c.recommendations, java.util.List.of());
        if (!c.news.isEmpty()) CliSupport.showNews(ctx, c.news, java.util.List.of());
        if (c.playtime != null) CliSupport.showPlaytime(ctx, c.playtime);
        if (c.calendar != null) CliSupport.showCalendar(ctx, c.calendar);
        c.warnings.forEach(w -> ctx.println("Note: " + w));
    }
}
