package gameagent.cli.commands;

import java.util.List;

import gameagent.cli.CliContext;
import gameagent.cli.CliSupport;
import gameagent.model.Recommendation;

class CatalogCommand extends BaseCommand {
    CatalogCommand() {
        super("catalog", "/catalog", "Your saved personalised catalogue of recommended games.", "list");
    }

    @Override
    public void execute(String args, CliContext ctx) {
        List<Recommendation> all = ctx.agent().catalog().all();
        if (all.isEmpty()) {
            ctx.println("Your catalogue is empty. Try /recommend first.");
            return;
        }
        CliSupport.showRecommendations(ctx, all.size() > 15 ? all.subList(0, 15) : all, List.of());
        if (ctx.agent().catalog().isStale()) {
            ctx.println("Note: your preferences changed since this catalogue was built. Run /recommend to refresh it.");
        }
    }
}
