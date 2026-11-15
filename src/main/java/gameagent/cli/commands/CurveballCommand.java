package gameagent.cli.commands;

import gameagent.cli.CliContext;
import gameagent.cli.CliSupport;
import gameagent.recommend.RecommendationResult;

class CurveballCommand extends BaseCommand {
    CurveballCommand() {
        super("curveball", "/curveball [optional hint]", "Roll the dice: one popular game and one hidden gem.", "roll");
    }

    @Override
    public void execute(String args, CliContext ctx) throws Exception {
        ctx.println("Rolling...");
        RecommendationResult r = ctx.agent().curveball(args.trim());
        CliSupport.showRecommendations(ctx, r.recommendations(), r.warnings());
    }
}
