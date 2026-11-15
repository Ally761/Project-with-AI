package gameagent.friends;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import gameagent.model.OwnedGame;
import gameagent.profile.ProfileManager;
import gameagent.steam.SteamLibrary;
import gameagent.steam.SteamLibraryAnalyzer;
import gameagent.steam.SteamProfileException;
import gameagent.steam.SteamProfileException.Reason;
import gameagent.steam.SteamProfileRef;
import gameagent.steam.SteamProfileSource;
import gameagent.util.Json;

/**
 * The friends list: friends are added by public Steam profile link (no login, no API key), their visible games are
 * kept in a local file, and the list is shared read-only with the recommendation strategies through {@link FriendRoster}.
 */
public class FriendService implements FriendRoster {
    public static final int MAX_FRIENDS = 10;

    private final SteamProfileSource source;
    private final ProfileManager profiles;
    private final Path file;
    private final List<Friend> friends = new ArrayList<>();

    public FriendService(SteamProfileSource source, ProfileManager profiles, Path file) {
        this.source = source;
        this.profiles = profiles;
        this.file = file;
        load();
    }

    @Override
    public synchronized List<Friend> friends() { return List.copyOf(friends); }

    /** @param input a profile link, 17-digit Steam ID or custom URL name */
    public synchronized Friend add(String input) throws SteamProfileException {
        SteamProfileRef ref = SteamProfileRef.parse(input);
        SteamLibrary lib = fetch(ref);
        if (profiles.profile().steamLink().map(l -> l.steamId().equals(lib.steamId())).orElse(false)) {
            throw new SteamProfileException(Reason.INVALID_INPUT, "That's your own Steam profile. Give me a friend's profile link.");
        }
        boolean replacing = friends.stream().anyMatch(f -> f.steamId().equals(lib.steamId()));
        if (!replacing && friends.size() >= MAX_FRIENDS) {
            throw new SteamProfileException(Reason.INVALID_INPUT, "You already have " + MAX_FRIENDS + " friends. Remove one with /friends remove <name> first.");
        }
        Friend f = toFriend(lib);
        friends.removeIf(x -> x.steamId().equals(f.steamId()));
        friends.add(f);
        save();
        return f;
    }

    public synchronized boolean remove(String nameOrId) {
        Friend f = find(nameOrId);
        if (f == null) return false;
        friends.remove(f);
        save();
        return true;
    }

    /** Re-reads every friend's profile. A friend who has since gone private keeps their old data and is reported. */
    public synchronized List<String> refresh() {
        List<String> report = new ArrayList<>();
        for (Friend old : new ArrayList<>(friends)) {
            try {
                Friend fresh = toFriend(fetch(SteamProfileRef.parse(old.steamId())));
                friends.set(friends.indexOf(old), fresh);
            } catch (SteamProfileException e) {
                report.add(old.name() + ": " + e.getMessage());
            }
        }
        save();
        return report;
    }

    public synchronized CommonGames common(String name) throws FriendException {
        Friend f = find(name);
        if (f == null) throw new FriendException("I don't have a friend called \"" + name + "\". See /friends.");
        List<String> shared = new ArrayList<>();
        List<OwnedGame> theirs = new ArrayList<>();
        for (OwnedGame g : f.games()) {
            if (profiles.profile().owns(g.name())) shared.add(g.name());
            else theirs.add(g);
        }
        return new CommonGames(f.name(), shared, theirs, false);
    }

    /** By persona name (any case, or a unique prefix), 17-digit Steam ID, or custom URL name. Null when not found. */
    public synchronized Friend find(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) return null;
        String q = nameOrId.trim().toLowerCase(Locale.ROOT).replaceAll("/$", "");
        String tail = q.substring(q.lastIndexOf('/') + 1);
        List<Friend> prefix = new ArrayList<>();
        for (Friend f : friends) {
            if (f.name().equalsIgnoreCase(q) || f.steamId().equals(tail) || f.profileUrl().toLowerCase(Locale.ROOT).endsWith("/" + tail)) return f;
            if (f.name().toLowerCase(Locale.ROOT).startsWith(q)) prefix.add(f);
        }
        return prefix.size() == 1 ? prefix.get(0) : null;
    }

    private SteamLibrary fetch(SteamProfileRef ref) throws SteamProfileException {
        try {
            return source.fetchLibrary(ref);
        } catch (SteamProfileException e) {
            if (e.reason() == Reason.PROFILE_PRIVATE || e.reason() == Reason.GAMES_PRIVATE) {
                throw new SteamProfileException(e.reason(), "That friend's Steam profile is private, so I can't see what they play. "
                        + "Ask them to set \"My profile\" and \"Game details\" to Public, or pick a different friend.", e);
            }
            throw e;
        }
    }

    private static Friend toFriend(SteamLibrary lib) {
        List<OwnedGame> games = lib.games().stream().filter(g -> !SteamLibraryAnalyzer.isTool(g.name())).toList();
        return new Friend(lib.steamId(), lib.personaName(), "https://steamcommunity.com/profiles/" + lib.steamId(), games, Instant.now());
    }

    // ------------------------------------------------------------------ persistence

    private void load() {
        if (!Files.exists(file)) return;
        try {
            Map<String, Object> root = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            for (Object o : Json.list(root, "friends")) {
                Map<String, Object> m = Json.asObject(o);
                List<OwnedGame> games = new ArrayList<>();
                for (Object go : Json.list(m, "games")) {
                    Map<String, Object> g = Json.asObject(go);
                    games.add(new OwnedGame((long) Json.num(g, "appId", 0), Json.str(g, "name", ""), Json.num(g, "hours", 0), Json.num(g, "recent", 0)));
                }
                friends.add(new Friend(Json.str(m, "steamId", ""), Json.str(m, "name", ""), Json.str(m, "url", ""), games,
                        Instant.parse(Json.str(m, "updated", Instant.now().toString()))));
            }
        } catch (Exception e) {
            friends.clear(); // damaged file: start empty rather than stop the app
        }
    }

    private void save() {
        List<Object> list = new ArrayList<>();
        for (Friend f : friends) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("steamId", f.steamId());
            m.put("name", f.name());
            m.put("url", f.profileUrl());
            m.put("updated", f.updated().toString());
            List<Object> games = new ArrayList<>();
            for (OwnedGame g : f.games()) {
                Map<String, Object> gm = new LinkedHashMap<>();
                gm.put("appId", g.appId());
                gm.put("name", g.name());
                gm.put("hours", g.hours());
                gm.put("recent", g.hoursRecent());
                games.add(gm);
            }
            m.put("games", games);
            list.add(m);
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.pretty(Map.of("friends", list)), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Warning: could not save the friends list: " + e.getMessage());
        }
    }
}
