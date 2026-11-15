package gameagent.profile;

/** Notification sent to {@link ProfileListener}s whenever the profile changes. */
public record ProfileEvent(Type type, String summary) {
    public enum Type {
        /** Likes/dislikes/notes changed: recommendations and news may be out of date. */
        PREFERENCES_CHANGED,
        FEEDBACK_ADDED,
        /** A Steam profile was linked, refreshed or unlinked: owned games changed. */
        STEAM_CHANGED,
        /** Profile wiped. */
        RESET,
        /** Log / bookkeeping only: nothing recommendation-relevant changed. */
        LOG_ADDED;

        public boolean affectsRecommendations() { return this != LOG_ADDED; }
    }
}
