package gameagent.cli.commands;

import gameagent.cli.CliContext;
import gameagent.cli.CliSupport;
import gameagent.playtime.PlaytimeException;

class PlaytimeCommand extends BaseCommand {
    PlaytimeCommand() {
        super("playtime", "/playtime <game>", "How long a game will take you to finish, based on typical times and your own pace.", "hltb");
    }

    @Override
    public void execute(String args, CliContext ctx) {
        String game = args == null ? "" : args.trim();
        if (game.isEmpty()) {
            ctx.println("Which game? Example: /playtime Hades");
            return;
        }
        try {
            ctx.println("Estimating...");
            CliSupport.showPlaytime(ctx, ctx.agent().estimatePlaytime(game));
        } catch (PlaytimeException e) {
            ctx.println("! " + e.getMessage());
        }
    }
}
