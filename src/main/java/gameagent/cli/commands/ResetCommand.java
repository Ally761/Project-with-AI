package gameagent.cli.commands;

import gameagent.cli.CliContext;

class ResetCommand extends BaseCommand {
    ResetCommand() {
        super("reset", "/reset", "Erase your whole profile, catalogue and history (asks first).");
    }

    @Override
    public void execute(String args, CliContext ctx) {
        if (ctx.confirm("This permanently erases your preferences, ratings, history and catalogue. Continue?")) {
            ctx.agent().resetProfile();
            ctx.println("Profile erased.");
        } else {
            ctx.println("Cancelled.");
        }
    }
}
