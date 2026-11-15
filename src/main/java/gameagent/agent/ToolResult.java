package gameagent.agent;

/** What a tool returns to the agent loop: text the LLM can read, and whether it succeeded. */
public record ToolResult(boolean ok, String observation) {
    public static ToolResult ok(String text) { return new ToolResult(true, text); }

    public static ToolResult error(String text) { return new ToolResult(false, "ERROR: " + text); }
}
