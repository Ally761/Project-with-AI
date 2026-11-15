package gameagent.cli.commands;

import gameagent.cli.CliContext;
import gameagent.cli.CliSupport;
import gameagent.recommend.RecommendationResult;

class RecommendCommand extends BaseCommand {
    RecommendCommand() {
        super("recommend", "/recommend [count] [what you're in the mood for]",
                "Get personalised, store-verified game recommendations.", "rec", "r");
    }

    @Override
    public void execute(String args, CliContext ctx) throws Exception {
        String[] parts = args.trim().split("\\s+", 2);
        int count = 5;
        String query = args.trim();
        if (!args.isBlank() && parts[0].matches("\\d{1,2}")) {
            count = Integer.parseInt(parts[0]);
            query = parts.length > 1 ? parts[1] : "";
        }
        if (query.isBlank() && ctx.agent().profile().isEmpty()) {
            ctx.println("I don't know anything about your taste yet. Tell me what you like (just chat), use /like, or run /refine first.");
            return;
        }
        if (ctx.agent().needsMoreInfo() && query.isBlank()) {
            ctx.println("(Your profile is still thin, so these will be rough. Run /refine to improve them.)");
        }
        ctx.println("Finding games and checking them against the Steam catalogue...");
        RecommendationResult r = ctx.agent().recommend(query, count);
        CliSupport.showRecommendations(ctx, r.recommendations(), r.warnings());
    }
}
