package gameagent.agent;

/** Final answer text plus the structured things the tools produced during the turn. */
public record AgentReply(String text, ToolContext context) {}
