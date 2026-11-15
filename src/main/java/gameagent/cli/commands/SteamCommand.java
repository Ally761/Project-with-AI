package gameagent.cli.commands;

import java.util.Locale;
import java.util.Optional;

import gameagent.cli.CliContext;
import gameagent.model.OwnedGame;
import gameagent.model.SteamLink;
import gameagent.profile.UserProfile;
import gameagent.steam.SteamLinkService;
import gameagent.steam.SteamProfileException;

class SteamCommand extends BaseCommand {
    SteamCommand() {
        super("steam", "/steam [profile link | refresh | top | unlink]",
                "Link your public Steam profile so I learn from what you play.");
    }

    @Override
    public void execute(String args, CliContext ctx) {
        String a = args == null ? "" : args.trim();
        try {
            switch (a.toLowerCase(Locale.ROOT)) {
                case "" -> status(ctx);
                case "refresh" -> show(ctx, ctx.agent().refreshSteam());
                case "top" -> top(ctx);
                case "unlink" -> unlink(ctx);
                default -> show(ctx, ctx.agent().linkSteam(a));
            }
        } catch (SteamProfileException e) {
            ctx.println("! " + e.getMessage());
        }
    }

    private void status(CliContext ctx) {
        UserProfile p = ctx.agent().profile();
        Optional<SteamLink> link = p.steamLink();
        if (link.isEmpty()) {
            ctx.println("No Steam profile linked. Paste your profile link: /steam https://steamcommunity.com/id/yourname");
            ctx.println("It must be public (Steam > Profile > Edit Profile > Privacy Settings: \"My profile\" and \"Game details\" = Public).");
            ctx.println("Only public information is read. No password, login or API key is needed.");
            return;
        }
        SteamLink l = link.get();
        double hours = p.ownedGames().stream().mapToDouble(OwnedGame::hours).sum();
        ctx.println("Linked to " + l.personaName() + ": " + p.ownedGames().size() + " most-played games known, " + Math.round(hours)
                + " hours (" + l.profileUrl() + ", synced " + l.linkedAt().toString().substring(0, 10) + ").");
        ctx.println("Use /steam refresh to re-read it, /steam top for your most played, /steam unlink to remove it.");
    }

    private void show(CliContext ctx, SteamLinkService.LinkResult r) {
        ctx.println("Linked " + r.link().personaName() + ": read " + r.libraryCount() + (r.libraryCount() == 1 ? " game" : " games") + ", " + Math.round(r.totalHours()) + " hours between them.");
        if (!r.topPlayed().isEmpty()) {
            ctx.println("Most played: " + String.join(", ", r.topPlayed().stream()
                    .map(g -> g.name() + " (" + Math.round(g.hours()) + " h)").toList()));
        }
        if (r.applied().isEmpty()) {
            ctx.println("No new tastes to add (nothing new beyond what your profile already says).");
        } else {
            ctx.println("Added to your profile from your playtime:");
            r.applied().forEach(s -> ctx.println("  - " + s));
        }
        r.warnings().forEach(w -> ctx.println("Note: " + w));
        ctx.println("Games I know you own won't be recommended. Try /recommend.");
    }

    private void top(CliContext ctx) {
        UserProfile p = ctx.agent().profile();
        if (p.steamLink().isEmpty()) {
            ctx.println("No Steam profile linked. Use /steam <profile link>.");
            return;
        }
        int rank = 1;
        for (OwnedGame g : p.mostPlayed(15)) {
            ctx.println(String.format("  %2d. %-40s %7.0f h", rank++, g.name(), g.hours()));
        }
    }

    private void unlink(CliContext ctx) {
        UserProfile p = ctx.agent().profile();
        if (p.steamLink().isEmpty()) {
            ctx.println("No Steam profile is linked.");
            return;
        }
        if (!ctx.confirm("Unlink " + p.steamLink().get().personaName() + " and forget the library? Owned games may be recommended again.")) {
            ctx.println("Cancelled.");
            return;
        }
        boolean remove = ctx.confirm("Also remove the likes that were imported from Steam? (things you said yourself are kept)");
        var removed = ctx.agent().unlinkSteam(remove);
        ctx.println("Steam profile unlinked." + (removed.isEmpty() ? "" : " Removed " + removed.size() + " imported preferences."));
    }
}
