package gameagent.playtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import gameagent.model.OwnedGame;
import gameagent.profile.ProfileManager;
import gameagent.util.Names;

/**
 * "How long will this game take me?" Gets the typical time from a third-party {@link GameLengthSource} (falling back to the
 * AI-backed {@link PlaytimeEstimator}), gathers the user's own evidence from the profile (Steam hours), and lets a
 * {@link PlaytimeStrategy} combine them.
 */
public class PlaytimeService {
    static final int HISTORY_GAMES = 8;
    /** Played at least this share of the typical time: probably got through it. */
    static final double FINISHED_FROM = 0.4;
    /** Played more than this many times the typical time: replays or endless play says nothing about pace. */
    static final double FINISHED_UNTIL = 2.5;

    /** A typical time plus where it came from. {@code length} is null for AI figures. */
    private record Typical(GameEstimate estimate, GameLength length) {}

    private final GameLengthSource lengths;
    private final PlaytimeEstimator estimator;
    private final ProfileManager profiles;
    private final PlaytimeStrategy general = new GeneralPlaytimeStrategy();
    private final PlaytimeStrategy pace = new PacePlaytimeStrategy();
    private final Map<String, Typical> cache = new LinkedHashMap<>();

    public PlaytimeService(GameLengthSource lengths, PlaytimeEstimator estimator, ProfileManager profiles) {
        this.lengths = lengths;
        this.estimator = estimator;
        this.profiles = profiles;
    }

    public synchronized PlaytimeResult estimate(String game) throws PlaytimeException {
        if (game == null || game.isBlank()) throw new PlaytimeException("Which game should I estimate?");
        String name = game.trim();
        List<OwnedGame> history = profiles.profile().mostPlayed(HISTORY_GAMES);
        List<String> notes = new ArrayList<>();

        List<String> wanted = new ArrayList<>();
        wanted.add(name);
        for (OwnedGame o : history) if (wanted.stream().noneMatch(w -> Names.norm(w).equals(Names.norm(o.name())))) wanted.add(o.name());
        gather(wanted, name, notes);

        Typical typical = cache.get(Names.norm(name));
        if (typical == null) throw new PlaytimeException("I couldn't find reliable playtime data for \"" + name + "\". Check the spelling, or try the full title.");

        double logged = 0;
        List<PaceSample> samples = new ArrayList<>();
        for (OwnedGame o : history) {
            if (Names.norm(o.name()).equals(Names.norm(name))) logged = o.hours();
            Typical t = cache.get(Names.norm(o.name()));
            if (t == null || Names.norm(o.name()).equals(Names.norm(name))) continue;
            double ratio = o.hours() / t.estimate().hours();
            if (ratio >= FINISHED_FROM && ratio <= FINISHED_UNTIL) samples.add(new PaceSample(o.name(), t.estimate().hours(), o.hours()));
        }
        PlaytimeStrategy strategy = samples.size() >= PacePlaytimeStrategy.MIN_SAMPLES ? pace : general;
        PlaytimeResult r = strategy.estimate(name, typical.estimate(), samples, logged);

        List<String> all = new ArrayList<>();
        GameLength len = typical.length();
        if (len != null) {
            all.add(String.format(Locale.ROOT, "Typical time from %s (%d player%s): %s%s.", len.source(), len.submissions(),
                    len.submissions() == 1 ? "" : "s", hoursText(len.typicalHours()),
                    len.hastyHours() > 0 && len.completeHours() > 0
                            ? String.format(Locale.ROOT, ", between %s (rushed) and %s (completionist)", hoursText(len.hastyHours()), hoursText(len.completeHours())) : ""));
        } else {
            all.add("Typical time is an AI estimate" + (lengths.configured() ? " (" + lengths.name() + " had no data for this game)" : "")
                    + ", not data from a game-length database.");
        }
        all.addAll(notes);
        all.addAll(r.notes());
        if (r.strategy().equals("general") && profiles.profile().steamLink().isEmpty()) all.add("Link your Steam profile (/steam) so I can learn your pace.");
        if (logged > 0) all.add(String.format(Locale.ROOT, "You have already logged %.0f h in it on Steam (that count includes idle time).", logged));
        if (len == null && !lengths.configured()) all.add("For real community data, add free IGDB keys to .env (see .env.example).");
        return new PlaytimeResult(r.game(), r.generalHours(), r.personalHours(), r.personalized(), r.paceFactor(), r.samples(),
                r.strategy(), r.loggedHours(), all, len != null ? len.source() : "AI estimate",
                len == null ? 0 : len.hastyHours(), len == null ? 0 : len.completeHours(), len == null ? 0 : len.submissions());
    }

    /** Fills the cache for every wanted game: third-party data first, the AI for whatever the source lacks. */
    private void gather(List<String> wanted, String target, List<String> notes) throws PlaytimeException {
        boolean sourceUsable = lengths.configured();
        List<String> needAi = new ArrayList<>();
        for (String g : wanted) {
            if (cache.containsKey(Names.norm(g))) continue;
            Optional<GameLength> found = Optional.empty();
            if (sourceUsable) {
                try {
                    found = lengths.lookup(g);
                } catch (PlaytimeException e) {
                    sourceUsable = false; // do not hammer a source that is failing; the AI covers the rest
                    notes.add(lengths.name() + " is unavailable (" + e.getMessage() + ") so I used the AI estimate instead.");
                }
            }
            if (found.isPresent()) {
                GameLength l = found.get();
                cache.put(Names.norm(g), new Typical(new GameEstimate(l.typicalHours(), List.of(), false), l));
            } else {
                needAi.add(g);
            }
        }
        if (needAi.isEmpty()) return;
        try {
            for (Map.Entry<String, GameEstimate> e : estimator.estimate(needAi).entrySet()) {
                if (!e.getValue().isUnknown()) cache.put(Names.norm(e.getKey()), new Typical(e.getValue(), null));
            }
        } catch (PlaytimeException e) {
            if (!cache.containsKey(Names.norm(target))) throw e; // the game asked about has no figure at all
        }
    }

    private static String hoursText(double h) {
        return h < 10 ? String.format(Locale.ROOT, "%.1f h", h) : String.format(Locale.ROOT, "%.0f h", h);
    }
}
