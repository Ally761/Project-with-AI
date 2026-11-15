package gameagent.steam;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import gameagent.data.GameDataSource;
import gameagent.model.OwnedGame;
import gameagent.model.SteamLink;
import gameagent.profile.ConflictResolver;
import gameagent.profile.ProfileManager;
import gameagent.steam.SteamProfileException.Reason;

/**
 * Links a public Steam profile to the user's profile: reads the library, imports tastes from playtime
 * (without overriding anything the user said) and remembers what is owned so it is never recommended.
 */
public class SteamLinkService {
    /** What a link or refresh did, for showing to the user. */
    public record LinkResult(SteamLink link, int libraryCount, double totalHours, List<OwnedGame> topPlayed,
                             List<String> applied, List<String> warnings) {}

    private final SteamProfileSource source;
    private final SteamLibraryAnalyzer analyzer;
    private final ProfileManager profiles;

    public SteamLinkService(SteamProfileSource source, GameDataSource data, ProfileManager profiles) {
        this.source = source;
        this.analyzer = new SteamLibraryAnalyzer(data);
        this.profiles = profiles;
    }

    public Optional<SteamLink> current() {
        return profiles.profile().steamLink();
    }

    /** @param input a profile link, 17-digit Steam ID or custom URL name */
    public LinkResult link(String input) throws SteamProfileException {
        SteamProfileRef ref = SteamProfileRef.parse(input);
        SteamLibrary fetched = source.fetchLibrary(ref);
        // Tools and extras (dedicated servers, SDKs, wallpaper apps...) would only distort counts and "most played".
        SteamLibrary lib = new SteamLibrary(fetched.steamId(), fetched.personaName(),
                fetched.games().stream().filter(g -> !SteamLibraryAnalyzer.isTool(g.name())).toList(), fetched.complete());
        List<String> warnings = new ArrayList<>();

        Optional<SteamLink> previous = current();
        if (previous.isPresent() && !previous.get().steamId().equals(lib.steamId())) {
            warnings.add("Replaced the previously linked account (" + previous.get().personaName()
                    + "). Tastes imported from it were kept; use /steam unlink first if you want them removed.");
        }

        if (!lib.complete()) {
            warnings.add("Without signing in, Steam only shows a public profile's few most-played games, so I read "
                    + lib.games().size() + (lib.games().size() == 1 ? " game" : " games")
                    + ". Your other games aren't visible to me, so some you own may still be recommended.");
        }
        SteamLibraryAnalyzer.Plan plan = analyzer.plan(lib, profiles.profile());
        warnings.addAll(plan.warnings());
        List<String> applied = List.of();
        if (!plan.update().isEmpty()) {
            applied = profiles.apply(plan.update(), ConflictResolver.KEEP_EXISTING).applied();
        }

        SteamLink link = new SteamLink(lib.steamId(), lib.personaName(),
                "https://steamcommunity.com/profiles/" + lib.steamId(), Instant.now());
        profiles.linkSteam(link, lib.games());

        double total = lib.games().stream().mapToDouble(OwnedGame::hours).sum();
        return new LinkResult(link, lib.games().size(), total, profiles.profile().mostPlayed(5), applied, warnings);
    }

    /** Re-reads the already linked profile (new games, new playtime). */
    public LinkResult refresh() throws SteamProfileException {
        SteamLink link = current().orElseThrow(() -> new SteamProfileException(Reason.INVALID_INPUT,
                "No Steam profile is linked yet. Use /steam <profile link> first."));
        return link(link.profileUrl());
    }

    /** @return the imported preferences that were removed (empty unless requested) */
    public List<String> unlink(boolean removeImported) {
        return profiles.unlinkSteam(removeImported);
    }
}
