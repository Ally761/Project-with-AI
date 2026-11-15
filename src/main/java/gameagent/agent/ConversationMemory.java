package gameagent.agent;

import java.util.ArrayList;
import java.util.List;

import gameagent.llm.Message;

/** Short-term memory: the most recent turns of the conversation (long-term memory is the UserProfile). */
public class ConversationMemory {
    private final int maxMessages;
    private final List<Message> messages = new ArrayList<>();

    public ConversationMemory(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public synchronized void addTurn(String user, String assistant) {
        messages.add(Message.user(user));
        messages.add(Message.assistant(assistant));
        while (messages.size() > maxMessages) messages.remove(0);
        // never start with an assistant message
        while (!messages.isEmpty() && messages.get(0).role() != gameagent.llm.Role.USER) messages.remove(0);
    }

    public synchronized List<Message> snapshot() {
        return new ArrayList<>(messages);
    }

    public synchronized void clear() {
        messages.clear();
    }
}
