package gameagent.cli.commands;

import gameagent.model.FeedbackResult;
import gameagent.cli.CliContext;

class RateCommand extends BaseCommand {
    RateCommand() {
        super("rate", "/rate <game> | <1-5> | [comment]", "Give feedback on a game you played; I learn from your rating and comment.", "feedback");
    }

    @Override
    public void execute(String args, CliContext ctx) {
        String[] parts = args.split("\\|", 3);
        if (parts.length < 2) {
            ctx.println("Usage: " + usage() + "   e.g. /rate Hades | 5 | loved the fast combat but the story was meh");
            return;
        }
        int rating;
        try {
            rating = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            ctx.println("The rating must be a number from 1 to 5.");
            return;
        }
        try {
            FeedbackResult r = ctx.agent().submitFeedback(parts[0].trim(), rating, parts.length > 2 ? parts[2] : "");
            ctx.println("Saved your " + rating + "/5 for " + parts[0].trim() + ".");
            r.changes().forEach(s -> ctx.println("  + " + s));
            r.warnings().forEach(s -> ctx.println("  Note: " + s));
        } catch (IllegalArgumentException e) {
            ctx.println(e.getMessage());
        }
    }
}
