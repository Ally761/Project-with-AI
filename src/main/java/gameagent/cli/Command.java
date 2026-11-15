package gameagent.cli;

import java.util.List;

/** Command pattern: each slash command is a small object that the CLI looks up by name. */
public interface Command {
    String name();

    default List<String> aliases() { return List.of(); }

    String usage();

    String help();

    void execute(String args, CliContext ctx) throws Exception;
}
