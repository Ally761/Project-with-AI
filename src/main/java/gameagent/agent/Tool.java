package gameagent.agent;

import java.util.Map;

/** Something the agent can decide to do. The LLM sees name() and description() and picks tools by itself. */
public interface Tool {
    String name();

    /** Includes the argument names/meaning; shown to the LLM verbatim. */
    String description();

    ToolResult execute(Map<String, Object> args, ToolContext ctx);
}
