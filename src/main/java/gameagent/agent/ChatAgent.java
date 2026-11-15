package gameagent.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import gameagent.llm.LLMClient;
import gameagent.llm.LLMException;
import gameagent.llm.LLMRequest;
import gameagent.llm.Message;
import gameagent.llm.PromptBuilder;
import gameagent.profile.ProfileManager;
import gameagent.util.Json;
import gameagent.util.JsonException;

/**
 * The conversational agent. Each user message runs a bounded reason-act loop:
 * the LLM either answers or asks for a tool; tool results are fed back until it answers.
 * The protocol is plain JSON in text, so it works with any LLMClient, not only models with native function calling.
 */
public class ChatAgent {
    static final int MAX_TOOL_STEPS = 4;

    private final LLMClient llm;
    private final ToolRegistry tools;
    private final ProfileManager profiles;
    private final ConversationMemory memory;

    public ChatAgent(LLMClient llm, ToolRegistry tools, ProfileManager profiles, ConversationMemory memory) {
        this.llm = llm;
        this.tools = tools;
        this.profiles = profiles;
        this.memory = memory;
    }

    public AgentReply chat(String userText) throws LLMException {
        ToolContext ctx = new ToolContext();
        profiles.log("chat", userText);

        List<Message> convo = new ArrayList<>(memory.snapshot());
        convo.add(Message.user(userText));
        List<String> seenCalls = new ArrayList<>();
        String reply = null;

        for (int step = 0; step <= MAX_TOOL_STEPS && reply == null; step++) {
            boolean mustFinish = step == MAX_TOOL_STEPS;
            if (mustFinish) convo.add(Message.user("SYSTEM: Tool budget used up. Give your final reply now with \"tool\": null."));

            // The system prompt is rebuilt every step so it always reflects the latest profile.
            String system = PromptBuilder.chatSystem(profiles.profile().toPromptSummary(), tools.describeAll());
            String raw = llm.complete(LLMRequest.json(system, convo, 0.6));
            Map<String, Object> action = parseOrRepair(raw, convo, system);

            if (action == null) { // model ignored the format entirely: treat the text as the answer
                reply = raw.trim();
                break;
            }
            String toolName = Json.str(action, "tool");
            if (toolName == null || toolName.isBlank() || toolName.equalsIgnoreCase("null") || mustFinish) {
                reply = Json.str(action, "reply", "").trim();
                break;
            }

            Map<String, Object> args = Json.obj(action, "args");
            if (args == null) args = Map.of();
            String callKey = toolName + Json.stringify(args);
            convo.add(Message.assistant(Json.stringify(action)));

            ToolResult result;
            Optional<Tool> tool = tools.find(toolName);
            if (tool.isEmpty()) {
                result = ToolResult.error("Unknown tool \"" + toolName + "\". Available: " + tools.describeAll());
            } else if (seenCalls.contains(callKey)) {
                result = ToolResult.error("You already made this exact call. Use the earlier result and answer the user.");
            } else {
                try {
                    result = tool.get().execute(args, ctx);
                } catch (RuntimeException e) {
                    result = ToolResult.error("Tool crashed: " + e.getMessage());
                }
                ctx.toolsUsed.add(toolName);
            }
            seenCalls.add(callKey);
            convo.add(Message.user("TOOL_RESULT " + toolName + ": " + result.observation()));
        }

        if (reply == null || reply.isBlank()) {
            reply = ctx.recommendations.isEmpty() ? "Done." : "Here are some picks for you.";
        }
        memory.addTurn(userText, reply);
        return new AgentReply(reply, ctx);
    }

    /** Parses the model output; on failure asks once for a corrected reply. Returns null if still unusable. */
    private Map<String, Object> parseOrRepair(String raw, List<Message> convo, String system) throws LLMException {
        try {
            return Json.parseLenientObject(raw);
        } catch (JsonException first) {
            List<Message> retry = new ArrayList<>(convo);
            retry.add(Message.assistant(raw));
            retry.add(Message.user("SYSTEM: That was not valid JSON. Reply again with ONLY the JSON object described in RESPONSE FORMAT."));
            String second = llm.complete(LLMRequest.json(system, retry, 0.2));
            try {
                return Json.parseLenientObject(second);
            } catch (JsonException again) {
                return null;
            }
        }
    }
}
