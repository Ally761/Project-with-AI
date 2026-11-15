package gameagent.cli.commands;

import gameagent.cli.CommandRegistry;

/** Builds the standard command set. */
public final class Commands {
    private Commands() {}

    public static CommandRegistry standard() {
        return new CommandRegistry()
                .register(new HelpCommand())
                .register(new RecommendCommand())
                .register(new CurveballCommand())
                .register(new NewsCommand())
                .register(new ProfileCommand())
                .register(new LikeCommand(true))
                .register(new LikeCommand(false))
                .register(new ForgetCommand())
                .register(new NoteCommand())
                .register(new RateCommand())
                .register(new RefineCommand())
                .register(new CatalogCommand())
                .register(new HistoryCommand())
                .register(new ResetCommand())
                .register(new QuitCommand());
    }
}
