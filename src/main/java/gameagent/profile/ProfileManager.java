package gameagent.profile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import gameagent.model.GameFeedback;
import gameagent.model.LogEntry;

/**
 * Single writer of the {@link UserProfile}. Applies updates, resolves conflicts, and publishes
 * {@link ProfileEvent}s to registered {@link ProfileListener}s (Observer pattern) so that the catalog,
 * news service and persistence react automatically instead of every feature calling every other.
 */
public class ProfileManager {
    private static final int MAX_LOG = 500;
    private static final int MAX_SHOWN = 200;

    /** What actually happened when an update was applied (for showing the user). */
    public record ApplyResult(List<String> applied, List<String> skipped) {
        public boolean changedAnything() { return !applied.isEmpty(); }
    }

    private UserProfile profile;
    private final List<ProfileListener> listeners = new CopyOnWriteArrayList<>();

    public ProfileManager(UserProfile profile) {
        this.profile = profile;
    }

    public UserProfile profile() { return profile; }

    public void addListener(ProfileListener l) { listeners.add(l); }

    private void fire(ProfileEvent.Type type, String summary) {
        ProfileEvent e = new ProfileEvent(type, summary);
        for (ProfileListener l : listeners) {
            try {
                l.onProfileChanged(e);
            } catch (RuntimeException ex) {
                System.err.println("Profile listener failed: " + ex);
            }
        }
    }

    public static double initialWeight(String source) {
        switch (source == null ? "" : source) {
            case "user": case "refinement": return 3.0;
            case "steam": return 2.5;
            default: return 2.0; // chat / feedback inferred
        }
    }

    // ------------------------------------------------------------ updates

    public ApplyResult apply(ProfileUpdate update, ConflictResolver resolver) {
        List<String> applied = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        String src = update.source();

        for (ProfileUpdate.Change ch : update.changes()) {
            String label = ch.category().name().toLowerCase() + " \"" + ch.name() + "\"";
            Optional<Preference> existing = profile.find(ch.category(), ch.name());

            if (ch.op() == ProfileUpdate.Op.REMOVE) {
                if (existing.isPresent()) {
                    profile.prefMap().remove(UserProfile.key(ch.category(), ch.name()));
                    applied.add("Removed " + label);
                } else {
                    skipped.add("Nothing to remove for " + label);
                }
                continue;
            }

            if (existing.isEmpty()) {
                profile.prefMap().put(UserProfile.key(ch.category(), ch.name()),
                        new Preference(ch.category(), ch.name(), ch.sentiment(), initialWeight(src), src, Instant.now()));
                applied.add((ch.sentiment() == Sentiment.LIKE ? "Likes " : "Dislikes ") + label);
            } else if (existing.get().sentiment() == ch.sentiment()) {
                existing.get().reinforce(src);
                applied.add("Reinforced " + (ch.sentiment() == Sentiment.LIKE ? "like of " : "dislike of ") + label);
            } else {
                ConflictResolver.Resolution r = resolver.resolve(existing.get(), ch.sentiment());
                if (r == ConflictResolver.Resolution.USE_NEW) {
                    existing.get().flip(ch.sentiment(), initialWeight(src), src);
                    applied.add("Changed " + label + " to " + (ch.sentiment() == Sentiment.LIKE ? "liked" : "disliked"));
                } else {
                    skipped.add("Kept existing " + (ch.sentiment() == Sentiment.LIKE ? "dislike" : "like") + " of " + label);
                }
            }
        }

        for (String n : update.addNotes()) {
            boolean dup = profile.noteList().stream().anyMatch(x -> x.equalsIgnoreCase(n));
            if (dup) {
                skipped.add("Note already saved: " + n);
            } else {
                profile.noteList().add(n);
                applied.add("Noted: " + n);
            }
        }

        if (!applied.isEmpty()) {
            appendLog("preferences/" + src, String.join("; ", applied));
            fire(ProfileEvent.Type.PREFERENCES_CHANGED, String.join("; ", applied));
        }
        return new ApplyResult(applied, skipped);
    }

    public boolean removeNote(int oneBasedIndex) {
        if (oneBasedIndex < 1 || oneBasedIndex > profile.noteList().size()) return false;
        String removed = profile.noteList().remove(oneBasedIndex - 1);
        appendLog("preferences/user", "Removed note: " + removed);
        fire(ProfileEvent.Type.PREFERENCES_CHANGED, "Removed note");
        return true;
    }

    /** Latest feedback for the same game replaces the older one. */
    public void recordFeedback(GameFeedback fb) {
        profile.feedbackList().removeIf(f -> UserProfile.normalize(f.gameName()).equals(UserProfile.normalize(fb.gameName())));
        profile.feedbackList().add(fb);
        appendLog("feedback", fb.gameName() + " " + fb.rating() + "/5 " + fb.comment());
        fire(ProfileEvent.Type.FEEDBACK_ADDED, fb.gameName());
    }

    public void markShown(List<String> gameNames) {
        profile.shownSet().addAll(gameNames);
        while (profile.shownSet().size() > MAX_SHOWN) {
            profile.shownSet().remove(profile.shownSet().iterator().next());
        }
        fire(ProfileEvent.Type.LOG_ADDED, "shown games updated");
    }

    /** Adds a line to the interaction log. */
    public void log(String type, String text) {
        appendLog(type, text);
        fire(ProfileEvent.Type.LOG_ADDED, text);
    }

    private void appendLog(String type, String text) {
        profile.logList().add(new LogEntry(Instant.now(), type, text));
        while (profile.logList().size() > MAX_LOG) profile.logList().remove(0);
    }

    public void reset() {
        profile = new UserProfile();
        fire(ProfileEvent.Type.RESET, "profile reset");
    }

    public void saveNow(ProfileRepository repo) throws IOException {
        repo.save(profile);
    }
}
