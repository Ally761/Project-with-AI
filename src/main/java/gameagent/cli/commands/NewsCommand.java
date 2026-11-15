package gameagent.cli.commands;

import gameagent.cli.CliContext;
import gameagent.cli.CliSupport;
import gameagent.news.NewsResult;

class NewsCommand extends BaseCommand {
    NewsCommand() {
        super("news", "/news [topic]", "Gaming news ranked for your interests.");
    }

    @Override
    public void execute(String args, CliContext ctx) throws Exception {
        ctx.println("Loading news...");
        NewsResult r = ctx.agent().news(args.trim(), 6);
        if (r.items().isEmpty()) {
            ctx.println("No matching news found.");
        } else {
            CliSupport.showNews(ctx, r.items(), r.warnings());
        }
        if (r.fromCache()) ctx.println("(Stored news from " + r.fetchedAt() + ")");
        else if (!r.aiRanked() && !r.items().isEmpty()) ctx.println("(Ranked by keyword matching, not AI)");
    }
}
