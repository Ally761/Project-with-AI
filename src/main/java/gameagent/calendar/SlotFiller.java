package gameagent.calendar;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** The shared mechanics of filling slots with sessions; strategies differ only in the two parameters. */
final class SlotFiller {
    /** Leftover time in a slot smaller than this is not worth starting another game for. */
    static final int MIN_CHUNK = 30;

    private SlotFiller() {}

    /**
     * @param maxChunk longest single session in minutes
     * @param rotate   when true the next pick starts after the game played last (variety); otherwise priority order (focus)
     */
    static List<Session> fill(List<Occurrence> slots, List<PlanGame> games, int maxChunk, boolean rotate) {
        List<Session> out = new ArrayList<>();
        int pointer = 0;
        for (Occurrence o : slots) {
            int left = o.minutes();
            LocalTime t = o.start();
            boolean first = true;
            while (first || left >= MIN_CHUNK) {
                first = false;
                int idx = pick(games, o.vibe(), rotate ? pointer : 0);
                if (idx < 0) break; // everything is already scheduled
                PlanGame g = games.get(idx);
                int cap = unfinished(games) <= 1 ? Integer.MAX_VALUE : maxChunk; // nothing to rotate to, so no reason to split
                int use = Math.min(Math.min(left, g.remaining), cap);
                if (use <= 0) break;
                g.remaining -= use;
                left -= use;
                out.add(new Session(o.date(), t, use, g.name, g.remaining == 0, o.vibe()));
                t = t.plusMinutes(use);
                if (rotate) pointer = (idx + 1) % games.size();
            }
        }
        return out;
    }

    private static int unfinished(List<PlanGame> games) {
        int n = 0;
        for (PlanGame g : games) if (g.remaining > 0) n++;
        return n;
    }

    /** Index of the next game to play, preferring one whose vibe fits the slot; -1 when all are finished. */
    static int pick(List<PlanGame> games, String vibe, int from) {
        int n = games.size();
        int firstUnfinished = -1;
        for (int k = 0; k < n; k++) {
            int i = (from + k) % n;
            PlanGame g = games.get(i);
            if (g.remaining <= 0) continue;
            if (vibe != null && !vibe.isBlank() && g.matches(vibe)) return i;
            if (firstUnfinished < 0) firstUnfinished = i;
        }
        return firstUnfinished;
    }
}
