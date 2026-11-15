package gameagent.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import gameagent.llm.LLMClient;
import gameagent.llm.LLMException;
import gameagent.llm.LLMRequest;
import gameagent.llm.PromptBuilder;
import gameagent.model.Recommendation;
import gameagent.playtime.GameEstimate;
import gameagent.playtime.PlaytimeEstimator;
import gameagent.playtime.PlaytimeException;
import gameagent.profile.ProfileEvent;
import gameagent.profile.ProfileListener;
import gameagent.profile.ProfileManager;
import gameagent.recommend.GameCatalog;
import gameagent.util.Json;
import gameagent.util.JsonException;
import gameagent.util.Names;

/**
 * The gaming calendar. Hybrid by design: the AI reads free text and estimates playtime, while the actual
 * scheduling is deterministic (a {@link SchedulingStrategy}), so a plan always fits the user's availability.
 * Observes the profile so a plan can be flagged as out of date when tastes change.
 */
public class CalendarService implements ProfileListener {
    public static final int MAX_WEEKS = 8;
    static final int MAX_GAMES = 8;

    private final CalendarStore store;
    private final CalendarStore.State state;
    private final GameCatalog catalog;
    private final PlaytimeEstimator estimator;
    private final LLMClient llm;
    private final ProfileManager profiles;
    private final Supplier<LocalDate> today;
    private final Map<String, GameEstimate> estimateCache = new LinkedHashMap<>();
    private boolean stale = false;

    public CalendarService(CalendarStore store, GameCatalog catalog, PlaytimeEstimator estimator, LLMClient llm,
                           ProfileManager profiles, Supplier<LocalDate> today) {
        this.store = store;
        this.state = store.load();
        this.catalog = catalog;
        this.estimator = estimator;
        this.llm = llm;
        this.profiles = profiles;
        this.today = today;
    }

    @Override
    public synchronized void onProfileChanged(ProfileEvent event) {
        if (state.plan != null && event.type().affectsRecommendations()) stale = true;
    }

    // ------------------------------------------------------------------ inputs

    public synchronized List<PlaySlot> availability() { return List.copyOf(state.slots); }

    public synchronized List<CalendarGame> games() { return List.copyOf(state.games); }

    public synchronized Optional<CalendarPlan> current() { return Optional.ofNullable(state.plan); }

    public synchronized boolean isStale() { return stale; }

    public synchronized CalendarPrefs adjustments() { return state.prefs; }

    /** Accepts the compact syntax ("mon,wed 20:00-22:00 chill") or free wording, which the AI converts. Replaces earlier availability. */
    public synchronized List<PlaySlot> setAvailability(String text) throws CalendarException {
        if (text == null || text.isBlank()) throw new CalendarException("Tell me when you can play, e.g. mon,wed 20:00-22:00 chill; sat 14:00-18:00");
        List<PlaySlot> slots;
        try {
            slots = AvailabilityParser.parse(text);
        } catch (CalendarException compactFailed) {
            slots = interpretAvailability(text);
        }
        state.slots = new ArrayList<>(slots);
        stale = state.plan != null;
        store.save(state);
        return List.copyOf(slots);
    }

    public synchronized void clearAvailability() {
        state.slots = new ArrayList<>();
        store.save(state);
    }

    private List<PlaySlot> interpretAvailability(String text) throws CalendarException {
        Map<String, Object> reply;
        try {
            reply = Json.parseLenientObject(llm.complete(LLMRequest.json(PromptBuilder.availabilitySystem(), text)));
        } catch (LLMException e) {
            throw new CalendarException("I couldn't read that availability and the AI is unavailable (" + e.getMessage()
                    + "). Use the compact form: mon,wed 20:00-22:00 chill; sat 14:00-18:00", e);
        } catch (JsonException e) {
            throw new CalendarException("The AI's reading of your availability was unreadable. Try the compact form: mon,wed 20:00-22:00", e);
        }
        List<PlaySlot> out = new ArrayList<>();
        for (Object o : Json.list(reply, "slots")) {
            Map<String, Object> m = Json.asObject(o);
            try {
                LocalTime start = LocalTime.parse(pad(Json.str(m, "start", "")));
                LocalTime end = LocalTime.parse(pad(Json.str(m, "end", "")));
                int minutes = (int) java.time.Duration.between(start, end).toMinutes();
                if (minutes < 15) continue;
                for (String d : Json.strList(m, "days")) {
                    DayOfWeek day = AvailabilityParser.day(d);
                    if (day != null) out.add(new PlaySlot(day, start, minutes, Json.str(m, "vibe", "")));
                }
            } catch (java.time.format.DateTimeParseException ignored) {
                // skip a block the model got wrong rather than failing the whole request
            }
        }
        if (out.isEmpty()) {
            throw new CalendarException("I couldn't work out your availability from that. Try: mon,wed 20:00-22:00 chill; sat 14:00-18:00");
        }
        return out;
    }

    private static String pad(String t) {
        return t.matches("\\d:\\d{2}") ? "0" + t : t;
    }

    /** Adds (or updates) a game to schedule. {@code hours} null means "let the estimator decide". */
    public synchronized void addGame(String name, Double hours) throws CalendarException {
        if (name == null || name.isBlank()) throw new CalendarException("Which game?");
        if (hours != null && (hours <= 0 || hours > 1000)) throw new CalendarException("Hours should be between 0 and 1000.");
        state.games.removeIf(g -> Names.norm(g.name()).equals(Names.norm(name)));
        state.games.add(new CalendarGame(name.trim(), hours));
        stale = state.plan != null;
        store.save(state);
    }

    public synchronized boolean removeGame(String name) {
        boolean removed = state.games.removeIf(g -> Names.norm(g.name()).equals(Names.norm(name)));
        if (removed) {
            stale = state.plan != null;
            store.save(state);
        }
        return removed;
    }

    // ------------------------------------------------------------------ planning

    /** Builds a plan from today on. @param weeks 1 = weekly, 4 = monthly (1..8) */
    public synchronized CalendarPlan createCalendar(int weeks, ScheduleMode mode) throws CalendarException {
        if (weeks < 1 || weeks > MAX_WEEKS) throw new CalendarException("Choose between 1 and " + MAX_WEEKS + " weeks.");
        if (state.slots.isEmpty()) {
            throw new CalendarException("I need to know when you can play first. Example: mon,wed 20:00-22:00 chill; sat 14:00-18:00");
        }
        CalendarPlan plan = build(weeks, mode == null ? ScheduleMode.FOCUS : mode);
        state.plan = plan;
        stale = false;
        store.save(state);
        return plan;
    }

    /** Applies a plain-English change ("no games on Friday", "play Hades first") and rebuilds the plan. */
    public synchronized CalendarPlan refineCalendar(String instruction) throws CalendarException {
        if (state.plan == null) throw new CalendarException("There is no calendar yet. Create one first.");
        if (instruction == null || instruction.isBlank()) throw new CalendarException("What should change? e.g. \"no games on Friday\"");
        Map<String, Object> json;
        try {
            json = Json.parseLenientObject(llm.complete(LLMRequest.json(PromptBuilder.calendarRefineSystem(),
                    PromptBuilder.calendarRefineUser(profiles.profile().toPromptSummary(), state.prefs.describe(), instruction))));
        } catch (LLMException e) {
            throw new CalendarException("The AI is unavailable, so I couldn't apply that change: " + e.getMessage(), e);
        } catch (JsonException e) {
            throw new CalendarException("The AI's reading of your change was unreadable; please rephrase it.", e);
        }
        Set<DayOfWeek> blocked = EnumSet.noneOf(DayOfWeek.class);
        for (String d : Json.strList(json, "blockDays")) {
            DayOfWeek day = AvailabilityParser.day(d);
            if (day != null) blocked.add(day);
        }
        int cap = Math.max(0, Math.min(Json.integer(json, "maxSessionMinutes", 0), 24 * 60));
        CalendarPrefs more = new CalendarPrefs(blocked, cap, Json.strList(json, "prioritize"), Json.strList(json, "exclude"));
        CalendarPrefs merged = state.prefs.merge(more, Json.bool(json, "reset", false));
        String modeText = Json.str(json, "mode", "");
        ScheduleMode mode = modeText.isBlank() ? state.plan.mode() : ScheduleMode.parse(modeText);
        CalendarPrefs previous = state.prefs;
        state.prefs = merged;
        try {
            CalendarPlan plan = build(state.plan.weeks(), mode);
            state.plan = plan;
            stale = false;
            store.save(state);
            return plan;
        } catch (CalendarException e) {
            state.prefs = previous;
            throw e;
        }
    }

    // ------------------------------------------------------------------ the deterministic part

    private CalendarPlan build(int weeks, ScheduleMode mode) throws CalendarException {
        List<String> warnings = new ArrayList<>();
        List<CalendarGame> wanted = gamesToSchedule();
        if (wanted.isEmpty()) {
            throw new CalendarException("There are no games to schedule yet. Get some recommendations, or add a game together with how many hours it takes.");
        }
        Map<String, GameEstimate> est = estimates(wanted, warnings);

        List<PlanGame> games = new ArrayList<>();
        Map<String, Boolean> guessed = new LinkedHashMap<>();
        for (CalendarGame g : wanted) {
            GameEstimate e = est.getOrDefault(g.name(), GameEstimate.unknown());
            boolean userHours = g.hours() != null;
            int minutes = (int) Math.round((userHours ? g.hours() : e.hours()) * 60);
            games.add(new PlanGame(g.name(), Math.max(15, minutes), e.vibes()));
            guessed.put(g.name(), !userHours);
        }

        LocalDate start = today.get();
        List<Occurrence> slots = occurrences(start, weeks);
        if (slots.isEmpty()) {
            throw new CalendarException("Every one of your available days is blocked by your adjustments (" + state.prefs.describe() + "). Clear the adjustments to continue.");
        }
        int availableMinutes = slots.stream().mapToInt(Occurrence::minutes).sum();
        int neededMinutes = games.stream().mapToInt(PlanGame::totalMinutes).sum();

        List<Session> sessions = mode.strategy().schedule(slots, games);

        List<GameProgress> progress = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        for (PlanGame g : games) {
            progress.add(new GameProgress(g.name(), g.totalMinutes() - g.remaining(), g.totalMinutes(), guessed.get(g.name())));
        }
        if (progress.stream().anyMatch(p -> !p.finished())) {
            limitedTimeAdvice(progress, neededMinutes, availableMinutes, weeks, mode, warnings, suggestions);
        }
        if (progress.stream().anyMatch(GameProgress::estimated)) {
            warnings.add("Hours marked ~ are AI estimates. Add a game with your own hours to replace an estimate.");
        }
        return new CalendarPlan(start, weeks, mode, sessions, progress, warnings, suggestions);
    }

    /** The "not enough free time" case: shorter games, splitting across sessions, or changing the schedule. */
    private void limitedTimeAdvice(List<GameProgress> progress, int needed, int available, int weeks, ScheduleMode mode,
                                   List<String> warnings, List<String> suggestions) {
        int missing = needed - available;
        warnings.add("Not everything fits: your games need about " + hours(needed) + " but you have " + hours(available)
                + " free in " + weeks + (weeks == 1 ? " week" : " weeks") + ".");
        List<GameProgress> unfinished = progress.stream().filter(p -> !p.finished()).toList();
        List<String> shortest = progress.stream().sorted(Comparator.comparingInt(GameProgress::totalMinutes))
                .limit(3).map(p -> p.name() + " (" + hours(p.totalMinutes()) + ")").toList();
        suggestions.add("Pick shorter games: " + String.join(", ", shortest) + " would fit more easily.");
        if (mode == ScheduleMode.FOCUS) {
            suggestions.add("Split games into sessions: the mix style rotates between games and spreads each across several shorter sessions.");
        } else {
            suggestions.add("Spread over more time: plan " + Math.min(MAX_WEEKS, weeks + 1) + " weeks and finish fewer games first (focus style).");
        }
        suggestions.add("Change the schedule: about " + hours(Math.max(missing, unfinished.stream().mapToInt(p -> p.totalMinutes() - p.scheduledMinutes()).sum()))
                + " more play time (or more weeks) would finish everything. Update your availability.");
    }

    private List<CalendarGame> gamesToSchedule() {
        List<CalendarGame> out = new ArrayList<>(state.games);
        for (Recommendation r : catalog.all()) {
            if (out.size() >= MAX_GAMES) break;
            String n = r.game().name();
            if (out.stream().noneMatch(g -> Names.norm(g.name()).equals(Names.norm(n)))) out.add(new CalendarGame(n, null));
        }
        out.removeIf(g -> state.prefs.isExcluded(g.name()));
        List<CalendarGame> ordered = new ArrayList<>();
        for (String p : state.prefs.priority()) {
            for (CalendarGame g : out) {
                if (Names.norm(g.name()).equals(Names.norm(p)) && !ordered.contains(g)) ordered.add(g);
            }
        }
        for (CalendarGame g : out) if (!ordered.contains(g)) ordered.add(g);
        return ordered.size() > MAX_GAMES ? new ArrayList<>(ordered.subList(0, MAX_GAMES)) : ordered;
    }

    private Map<String, GameEstimate> estimates(List<CalendarGame> wanted, List<String> warnings) {
        List<String> missing = new ArrayList<>();
        for (CalendarGame g : wanted) if (!estimateCache.containsKey(Names.norm(g.name()))) missing.add(g.name());
        if (!missing.isEmpty()) {
            try {
                for (Map.Entry<String, GameEstimate> e : estimator.estimate(missing).entrySet()) {
                    estimateCache.put(Names.norm(e.getKey()), e.getValue());
                }
            } catch (PlaytimeException e) {
                warnings.add("Couldn't estimate playtimes (" + e.getMessage() + "), so I assumed about "
                        + (int) GameEstimate.DEFAULT_HOURS + " hours per game where you gave none.");
            }
        }
        Map<String, GameEstimate> out = new LinkedHashMap<>();
        for (CalendarGame g : wanted) out.put(g.name(), estimateCache.getOrDefault(Names.norm(g.name()), GameEstimate.unknown()));
        return out;
    }

    List<Occurrence> occurrences(LocalDate start, int weeks) {
        List<Occurrence> out = new ArrayList<>();
        for (int i = 0; i < weeks * 7; i++) {
            LocalDate d = start.plusDays(i);
            if (state.prefs.blockedDays().contains(d.getDayOfWeek())) continue;
            for (PlaySlot s : state.slots) {
                if (s.day() != d.getDayOfWeek()) continue;
                int minutes = state.prefs.maxSessionMinutes() > 0 ? Math.min(s.minutes(), state.prefs.maxSessionMinutes()) : s.minutes();
                out.add(new Occurrence(d, s.start(), minutes, s.vibe() == null ? "" : s.vibe()));
            }
        }
        out.sort(Comparator.comparing(Occurrence::date).thenComparing(Occurrence::start));
        return out;
    }

    /** Clears refinements (blocked days, caps, priorities) so the next plan uses the plain availability. */
    public synchronized void resetAdjustments() {
        state.prefs = CalendarPrefs.none();
        stale = state.plan != null;
        store.save(state);
    }

    static String hours(int minutes) { return Minutes.hours(minutes); }
}
