package gameagent.cli.commands;

import java.util.List;
import java.util.stream.Collectors;

import gameagent.cli.CliContext;
import gameagent.model.GameFeedback;
import gameagent.profile.Category;
import gameagent.profile.Preference;
import gameagent.profile.Sentiment;
import gameagent.profile.UserProfile;

class ProfileCommand extends BaseCommand {
    ProfileCommand() {
        super("profile", "/profile", "Show everything I know about your tastes.", "me");
    }

    @Override
    public void execute(String args, CliContext ctx) {
        UserProfile p = ctx.agent().profile();
        p.steamLink().ifPresent(l -> ctx.println("Steam: linked to " + l.personaName() + " (" + p.ownedGames().size() + " most-played games known)."));
        if (p.isEmpty()) {
            ctx.println("Your profile is empty. Chat with me about games you like, or use /like and /refine.");
            return;
        }
        ctx.println("Your profile (number in brackets = strength 1-5):");
        section(ctx, "Liked genres", p.preferences(Category.GENRE, Sentiment.LIKE));
        section(ctx, "Disliked genres", p.preferences(Category.GENRE, Sentiment.DISLIKE));
        section(ctx, "Liked themes/tags", p.preferences(Category.TAG, Sentiment.LIKE));
        section(ctx, "Disliked themes/tags", p.preferences(Category.TAG, Sentiment.DISLIKE));
        section(ctx, "Liked games", p.preferences(Category.GAME, Sentiment.LIKE));
        section(ctx, "Disliked games", p.preferences(Category.GAME, Sentiment.DISLIKE));
        section(ctx, "Platforms", p.preferences(Category.PLATFORM, Sentiment.LIKE));
        List<String> notes = p.notes();
        if (!notes.isEmpty()) {
            ctx.println("  Notes:");
            for (int i = 0; i < notes.size(); i++) ctx.println("    " + (i + 1) + ". " + notes.get(i));
        }
        List<GameFeedback> fb = p.feedback();
        if (!fb.isEmpty()) {
            ctx.println("  Your ratings: " + fb.stream().map(f -> f.gameName() + " " + f.rating() + "/5").collect(Collectors.joining(", ")));
        }
    }

    private static void section(CliContext ctx, String title, List<Preference> items) {
        if (items.isEmpty()) return;
        ctx.println("  " + title + ": " + items.stream()
                .map(p -> p.name() + " [" + Math.round(p.weight() * 10) / 10.0 + "]")
                .collect(Collectors.joining(", ")));
    }
}
