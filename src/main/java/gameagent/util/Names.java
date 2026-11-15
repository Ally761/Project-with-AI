package gameagent.util;

import java.util.Locale;

public final class Names {
    private Names() {}

    /** Normalises a game title for comparison: lowercase, without trademark symbols, punctuation or spaces. */
    public static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\u2122\\u00ae\\u00a9]", "").replaceAll("[^a-z0-9]", "");
    }
}
