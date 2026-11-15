package gameagent.profile;

/** Decides what to do when a new opinion contradicts an existing one (e.g. "like RPG" vs "dislike RPG"). */
@FunctionalInterface
public interface ConflictResolver {
    enum Resolution { KEEP_EXISTING, USE_NEW }

    Resolution resolve(Preference existing, Sentiment incoming);

    /** The most recent statement wins (used for chat-inferred changes). */
    ConflictResolver NEWEST_WINS = (existing, incoming) -> Resolution.USE_NEW;

    /** Never override what is already stored. */
    ConflictResolver KEEP_EXISTING = (existing, incoming) -> Resolution.KEEP_EXISTING;
}
