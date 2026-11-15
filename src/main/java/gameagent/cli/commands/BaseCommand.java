package gameagent.cli.commands;

import java.util.List;

import gameagent.cli.Command;

abstract class BaseCommand implements Command {
    private final String name;
    private final String usage;
    private final String help;
    private final List<String> aliases;

    BaseCommand(String name, String usage, String help, String... aliases) {
        this.name = name;
        this.usage = usage;
        this.help = help;
        this.aliases = List.of(aliases);
    }

    @Override public String name() { return name; }
    @Override public List<String> aliases() { return aliases; }
    @Override public String usage() { return usage; }
    @Override public String help() { return help; }
}
