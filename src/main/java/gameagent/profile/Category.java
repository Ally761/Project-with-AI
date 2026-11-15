package gameagent.profile;

public enum Category {
    GENRE, TAG, GAME, PLATFORM;

    /** Lenient parsing of LLM / user text; unknown values become TAG. */
    public static Category parse(String s) {
        if (s == null) return TAG;
        switch (s.trim().toLowerCase()) {
            case "genre": case "genres": return GENRE;
            case "game": case "games": case "title": return GAME;
            case "platform": case "platforms": case "system": return PLATFORM;
            default: return TAG;
        }
    }
}
