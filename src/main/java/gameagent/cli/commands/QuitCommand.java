package gameagent.cli.commands;

import gameagent.cli.CliContext;

class QuitCommand extends BaseCommand {
    QuitCommand() {
        super("quit", "/quit", "Exit the program (your profile is saved automatically).", "exit", "q");
    }

    @Override
    public void execute(String args, CliContext ctx) {
        // handled by the CLI loop through QUIT_SIGNAL
        throw new gameagent.cli.QuitException();
    }
}
