package gameagent.cli.commands;

import java.util.List;
import java.util.Locale;

import gameagent.cli.CliContext;
import gameagent.cli.CliSupport;
import gameagent.friends.CommonGames;
import gameagent.friends.Friend;
import gameagent.friends.FriendException;
import gameagent.model.OwnedGame;
import gameagent.recommend.RecommendationException;
import gameagent.recommend.RecommendationResult;
import gameagent.steam.SteamProfileException;

class FriendsCommand extends BaseCommand {
    FriendsCommand() {
        super("friends", "/friends [add <profile link> | remove <name> | refresh | common <name> | recommend [name] [n]]",
                "Get game recommendations from what your Steam friends play (public profiles only).");
    }

    @Override
    public void execute(String args, CliContext ctx) {
        String a = args == null ? "" : args.trim();
        String[] parts = a.split("\\s+", 2);
        String sub = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1].trim() : "";
        try {
            switch (sub) {
                case "" , "list" -> list(ctx);
                case "add" -> add(ctx, rest);
                case "remove" -> remove(ctx, rest);
                case "refresh" -> refresh(ctx);
                case "common" -> common(ctx, rest);
                case "recommend", "rec" -> recommend(ctx, rest);
                default -> add(ctx, a); // "/friends <link>" is a natural shortcut
            }
        } catch (SteamProfileException | FriendException | RecommendationException e) {
            ctx.println("! " + e.getMessage());
        }
    }

    private void list(CliContext ctx) {
        List<Friend> friends = ctx.agent().friends();
        if (friends.isEmpty()) {
            ctx.println("No friends added yet. Give me a friend's public Steam profile link:");
            ctx.println("  /friends add https://steamcommunity.com/id/theirname");
            ctx.println("Then /friends recommend. Only public information is read; no login or API key is needed.");
            return;
        }
        for (Friend f : friends) {
            String top = String.join(", ", f.games().stream().limit(3).map(g -> g.name() + " (" + Math.round(g.hours()) + " h)").toList());
            ctx.println("  " + f.name() + " - " + f.games().size() + " visible games" + (top.isEmpty() ? "" : ": " + top));
        }
        ctx.println("Use /friends recommend, /friends common <name>, /friends refresh, /friends remove <name>.");
    }

    private void add(CliContext ctx, String link) throws SteamProfileException {
        Friend f = ctx.agent().addFriend(link);
        ctx.println("Added " + f.name() + ": " + f.games().size() + (f.games().size() == 1 ? " game" : " games") + " visible.");
        if (f.games().isEmpty()) ctx.println("Note: they show no games, so I can't learn anything from them yet.");
        ctx.println("Try /friends recommend.");
    }

    private void remove(CliContext ctx, String name) {
        ctx.println(ctx.agent().removeFriend(name) ? "Removed " + name + "." : "I don't have a friend called \"" + name + "\".");
    }

    private void refresh(CliContext ctx) {
        List<String> problems = ctx.agent().refreshFriends();
        ctx.println("Friends refreshed." + (problems.isEmpty() ? "" : " Some couldn't be updated (their old data is kept):"));
        problems.forEach(p -> ctx.println("  - " + p));
    }

    private void common(CliContext ctx, String name) throws FriendException {
        CommonGames c = ctx.agent().commonGames(name);
        ctx.println("You and " + c.friend() + " both have: " + (c.shared().isEmpty() ? "nothing I can see in common" : String.join(", ", c.shared())));
        if (!c.onlyTheirs().isEmpty()) {
            ctx.println(c.friend() + " plays that you don't have: " + String.join(", ",
                    c.onlyTheirs().stream().map(OwnedGame::name).toList()));
        }
    }

    private void recommend(CliContext ctx, String rest) throws RecommendationException {
        String friend = rest;
        int count = 5;
        String[] w = rest.split("\\s+");
        if (w.length > 0 && w[w.length - 1].matches("\\d{1,2}")) {
            count = Integer.parseInt(w[w.length - 1]);
            friend = String.join(" ", java.util.Arrays.copyOf(w, w.length - 1)).trim();
        }
        ctx.println("Looking at what your friends play...");
        RecommendationResult r = ctx.agent().recommendFromFriends(friend, count);
        CliSupport.showRecommendations(ctx, r.recommendations(), r.warnings());
    }
}
