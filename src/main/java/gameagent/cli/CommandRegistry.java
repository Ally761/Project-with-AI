package gameagent.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class CommandRegistry {
    private final Map<String, Command> byName = new LinkedHashMap<>();
    private final List<Command> ordered = new ArrayList<>();

    public CommandRegistry register(Command c) {
        ordered.add(c);
        byName.put(c.name().toLowerCase(), c);
        for (String a : c.aliases()) byName.put(a.toLowerCase(), c);
        return this;
    }

    public Optional<Command> find(String name) {
        return Optional.ofNullable(byName.get(name.toLowerCase()));
    }

    public List<Command> all() { return ordered; }
}
