package gameagent.steam;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which Steam profile the user means. Accepts what people actually paste: a full profile link
 * ({@code https://steamcommunity.com/id/name/} or {@code .../profiles/7656...}), the 17-digit Steam ID,
 * or just the custom URL name. Anything else is rejected, so only steamcommunity.com is ever contacted.
 */
public record SteamProfileRef(Kind kind, String value) {
    public enum Kind { STEAM_ID, VANITY }

    private static final Pattern STEAM_ID = Pattern.compile("7656\\d{13}");
    private static final Pattern VANITY = Pattern.compile("[A-Za-z0-9_-]{2,32}");
    private static final Pattern LINK = Pattern.compile(
            "^(?:https?://)?(?:www\\.)?steamcommunity\\.com/(id|profiles)/([^/?#\\s]+)", Pattern.CASE_INSENSITIVE);

    public static SteamProfileRef parse(String input) throws SteamProfileException {
        String s = input == null ? "" : input.trim().replaceAll("^[<\"']+|[>\"']+$", "");
        if (s.isEmpty()) throw invalid("Give me a Steam profile link, for example https://steamcommunity.com/id/yourname");

        if (STEAM_ID.matcher(s).matches()) return new SteamProfileRef(Kind.STEAM_ID, s);

        Matcher m = LINK.matcher(s);
        if (m.find()) {
            String value = m.group(2);
            if (m.group(1).equalsIgnoreCase("profiles")) {
                if (!STEAM_ID.matcher(value).matches()) throw invalid("That /profiles/ link should end in a 17-digit Steam ID.");
                return new SteamProfileRef(Kind.STEAM_ID, value);
            }
            if (!VANITY.matcher(value).matches()) throw invalid("That doesn't look like a valid Steam custom URL name.");
            return new SteamProfileRef(Kind.VANITY, value);
        }
        if (s.contains("/") || s.contains(".")) {
            throw invalid("I can only link steamcommunity.com profile links (https://steamcommunity.com/id/name or .../profiles/765...).");
        }
        if (VANITY.matcher(s).matches()) return new SteamProfileRef(Kind.VANITY, s);
        throw invalid("That doesn't look like a Steam profile link, 17-digit Steam ID or custom URL name.");
    }

    /** Path on steamcommunity.com, e.g. {@code /id/gabelogannewell}. */
    public String communityPath() {
        return (kind == Kind.STEAM_ID ? "/profiles/" : "/id/") + value;
    }

    private static SteamProfileException invalid(String msg) {
        return new SteamProfileException(SteamProfileException.Reason.INVALID_INPUT, msg);
    }
}
