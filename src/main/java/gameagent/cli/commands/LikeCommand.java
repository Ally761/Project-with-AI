package gameagent.cli.commands;

import gameagent.model.ApplyResult;
import gameagent.cli.CliContext;
import gameagent.profile.Category;
import gameagent.profile.ConflictResolver;
import gameagent.profile.ProfileUpdate;
import gameagent.profile.Sentiment;

/** Implements both /like and /dislike (they differ only in sentiment). */
class LikeCommand extends BaseCommand {
    private final boolean like;

    LikeCommand(boolean like) {
        super(like ? "like" : "dislike",
                (like ? "/like" : "/dislike") + " <genre|tag|game|platform> <name>",
                like ? "Add something you enjoy (e.g. /like genre Roguelike)." : "Add something you want to avoid (e.g. /dislike tag Grinding).");
        this.like = like;
    }

    @Override
    public void execute(String args, CliContext ctx) {
        String[] parts = args.trim().split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank() || !isCategory(parts[0])) {
            ctx.println("Usage: " + usage());
            return;
        }
        Category cat = Category.parse(parts[0]);
        ProfileUpdate u = new ProfileUpdate("user");
        if (like) u.like(cat, parts[1]); else u.dislike(cat, parts[1]);

        // Conflicting preferences: ask the user which one to keep
        ConflictResolver ask = (existing, incoming) -> ctx.confirm("You currently "
                + (existing.sentiment() == Sentiment.LIKE ? "like" : "dislike") + " \"" + existing.name() + "\". Switch to "
                + (incoming == Sentiment.LIKE ? "liking" : "disliking") + " it?")
                ? ConflictResolver.Resolution.USE_NEW : ConflictResolver.Resolution.KEEP_EXISTING;

        ApplyResult r = ctx.agent().updateProfile(u, ask);
        r.applied().forEach(s -> ctx.println("  + " + s));
        r.skipped().forEach(s -> ctx.println("  - " + s));
    }

    static boolean isCategory(String s) {
        String t = s.toLowerCase();
        return t.equals("genre") || t.equals("tag") || t.equals("game") || t.equals("platform");
    }
}
