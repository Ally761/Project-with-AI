package gameagent.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry register(Tool t) {
        tools.put(t.name(), t);
        return this;
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public String describeAll() {
        StringBuilder sb = new StringBuilder();
        for (Tool t : tools.values()) sb.append("- ").append(t.name()).append(": ").append(t.description()).append('\n');
        return sb.toString().trim();
    }
}
