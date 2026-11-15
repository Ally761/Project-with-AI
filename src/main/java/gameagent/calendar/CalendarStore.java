package gameagent.calendar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gameagent.util.Json;

/** Keeps the calendar state (availability, added games, adjustments, last plan) in a JSON file between sessions. */
public class CalendarStore {
    /** Everything that is saved. */
    public static final class State {
        public List<PlaySlot> slots = new ArrayList<>();
        public List<CalendarGame> games = new ArrayList<>();
        public CalendarPrefs prefs = CalendarPrefs.none();
        public CalendarPlan plan;
    }

    private final Path file;

    public CalendarStore(Path file) {
        this.file = file;
    }

    public State load() {
        State s = new State();
        if (!Files.exists(file)) return s;
        try {
            Map<String, Object> root = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            for (Object o : Json.list(root, "slots")) {
                Map<String, Object> m = Json.asObject(o);
                s.slots.add(new PlaySlot(DayOfWeek.valueOf(Json.str(m, "day")), LocalTime.parse(Json.str(m, "start")),
                        Json.integer(m, "minutes", 60), Json.str(m, "vibe", "")));
            }
            for (Object o : Json.list(root, "games")) {
                Map<String, Object> m = Json.asObject(o);
                s.games.add(new CalendarGame(Json.str(m, "name"), m.get("hours") == null ? null : Json.num(m, "hours", 0)));
            }
            Map<String, Object> p = Json.obj(root, "prefs");
            if (p != null) {
                Set<DayOfWeek> blocked = EnumSet.noneOf(DayOfWeek.class);
                for (String d : Json.strList(p, "blocked")) blocked.add(DayOfWeek.valueOf(d));
                s.prefs = new CalendarPrefs(blocked, Json.integer(p, "maxSession", 0), Json.strList(p, "priority"), Json.strList(p, "excluded"));
            }
            Map<String, Object> pl = Json.obj(root, "plan");
            if (pl != null) s.plan = readPlan(pl);
        } catch (Exception e) {
            // A damaged or older file must never stop the app: start with an empty calendar.
            return new State();
        }
        return s;
    }

    public void save(State s) {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> slots = new ArrayList<>();
        for (PlaySlot p : s.slots) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", p.day().name());
            m.put("start", p.start().toString());
            m.put("minutes", p.minutes());
            m.put("vibe", p.vibe() == null ? "" : p.vibe());
            slots.add(m);
        }
        root.put("slots", slots);
        List<Object> games = new ArrayList<>();
        for (CalendarGame g : s.games) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", g.name());
            if (g.hours() != null) m.put("hours", g.hours());
            games.add(m);
        }
        root.put("games", games);
        Map<String, Object> prefs = new LinkedHashMap<>();
        prefs.put("blocked", s.prefs.blockedDays().stream().map(Enum::name).toList());
        prefs.put("maxSession", s.prefs.maxSessionMinutes());
        prefs.put("priority", s.prefs.priority());
        prefs.put("excluded", s.prefs.excluded());
        root.put("prefs", prefs);
        if (s.plan != null) root.put("plan", writePlan(s.plan));
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.pretty(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Warning: could not save the calendar: " + e.getMessage());
        }
    }

    private static Map<String, Object> writePlan(CalendarPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("start", p.start().toString());
        m.put("weeks", p.weeks());
        m.put("mode", p.mode().name());
        List<Object> sessions = new ArrayList<>();
        for (Session s : p.sessions()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("date", s.date().toString());
            x.put("start", s.start().toString());
            x.put("minutes", s.minutes());
            x.put("game", s.game());
            x.put("completes", s.completesGame());
            x.put("vibe", s.vibe() == null ? "" : s.vibe());
            sessions.add(x);
        }
        m.put("sessions", sessions);
        List<Object> progress = new ArrayList<>();
        for (GameProgress g : p.progress()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("name", g.name());
            x.put("scheduled", g.scheduledMinutes());
            x.put("total", g.totalMinutes());
            x.put("estimated", g.estimated());
            progress.add(x);
        }
        m.put("progress", progress);
        m.put("warnings", p.warnings());
        m.put("suggestions", p.suggestions());
        return m;
    }

    private static CalendarPlan readPlan(Map<String, Object> m) {
        List<Session> sessions = new ArrayList<>();
        for (Object o : Json.list(m, "sessions")) {
            Map<String, Object> x = Json.asObject(o);
            sessions.add(new Session(LocalDate.parse(Json.str(x, "date")), LocalTime.parse(Json.str(x, "start")),
                    Json.integer(x, "minutes", 60), Json.str(x, "game"), Json.bool(x, "completes", false), Json.str(x, "vibe", "")));
        }
        List<GameProgress> progress = new ArrayList<>();
        for (Object o : Json.list(m, "progress")) {
            Map<String, Object> x = Json.asObject(o);
            progress.add(new GameProgress(Json.str(x, "name"), Json.integer(x, "scheduled", 0), Json.integer(x, "total", 0), Json.bool(x, "estimated", true)));
        }
        return new CalendarPlan(LocalDate.parse(Json.str(m, "start")), Json.integer(m, "weeks", 1),
                ScheduleMode.valueOf(Json.str(m, "mode", "FOCUS")), sessions, progress, Json.strList(m, "warnings"), Json.strList(m, "suggestions"));
    }
}
