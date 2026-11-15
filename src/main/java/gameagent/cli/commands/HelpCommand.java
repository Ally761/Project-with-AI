package gameagent.cli.commands;

import gameagent.cli.CliContext;
import gameagent.cli.Command;

class HelpCommand extends BaseCommand {
    HelpCommand() {
        super("help", "/help", "Show this list of commands.", "?");
    }

    @Override
    public void execute(String args, CliContext ctx) {
        ctx.println("Just type normally to chat (\"I loved Hades, find me something similar\"), or use a command:");
        ctx.println("");
        for (Command c : ctx.registry().all()) {
            ctx.println(String.format("  %-52s %s", c.usage(), c.help()));
        }
        ctx.println("");
    }
}
