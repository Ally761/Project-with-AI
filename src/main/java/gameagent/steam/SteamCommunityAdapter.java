package gameagent.steam;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import gameagent.model.OwnedGame;
import gameagent.steam.SteamProfileException.Reason;
import gameagent.util.HttpFetcher;

/**
 * Adapter: reads a public Steam Community profile the way a browser does without signing in: no API key, no login.
 * <p>
 * The profile feed ({@code /id/name/?xml=1} or {@code /profiles/ID/?xml=1}) gives the Steam ID, display name, privacy state
 * and the profile's <b>most-played games</b> with hours. Steam no longer serves the full games list to anonymous visitors
 * (it redirects to the login page), so that short list is all that can be read this way and the library is reported as incomplete.
 * Private profiles and hidden game details are reported with instructions rather than worked around.
 */
public class SteamCommunityAdapter implements SteamProfileSource {
    private static final String BASE = "https://steamcommunity.com";
    private static final Pattern APP_LINK = Pattern.compile("/app/(\\d+)");

    private final HttpFetcher http;
    private final String baseUrl;

    public SteamCommunityAdapter(HttpFetcher http) {
        this(http, BASE);
    }

    /** Base URL override exists so tests can point at a stub. */
    public SteamCommunityAdapter(HttpFetcher http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    @Override
    public SteamLibrary fetchLibrary(SteamProfileRef ref) throws SteamProfileException {
        Document profile = parseXml(get(baseUrl + ref.communityPath() + "/?xml=1"));
        Element root = profile.getDocumentElement();
        if (child(root, "error") != null) {
            throw new SteamProfileException(Reason.NOT_FOUND, "I couldn't find that Steam profile. Check the link or custom URL name.");
        }
        String steamId = text(root, "steamID64");
        if (steamId.isEmpty()) {
            throw new SteamProfileException(Reason.UNREADABLE, "Steam returned a profile page I couldn't read.");
        }
        String privacy = text(root, "privacyState");
        if (!privacy.isEmpty() && !privacy.equalsIgnoreCase("public")) {
            throw new SteamProfileException(Reason.PROFILE_PRIVATE,
                    "That Steam profile isn't public. In Steam open Profile > Edit Profile > Privacy Settings and set "
                            + "\"My profile\" and \"Game details\" to Public (you can switch them back after linking).");
        }
        String persona = text(root, "steamID");
        List<OwnedGame> games = mostPlayed(root);
        if (games.isEmpty()) throw gamesPrivate();
        return new SteamLibrary(steamId, persona.isEmpty() ? steamId : persona, games, false);
    }

    /** The profile's "most played" entries: total hours on record and hours in the last two weeks. */
    static List<OwnedGame> mostPlayed(Element profile) {
        List<OwnedGame> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        NodeList nodes = profile.getElementsByTagName("mostPlayedGame");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element g = (Element) nodes.item(i);
            String name = text(g, "gameName");
            if (name.isEmpty()) continue;
            Matcher m = APP_LINK.matcher(text(g, "gameLink"));
            long appId = m.find() ? Long.parseLong(m.group(1)) : 0;
            if (!seen.add(appId != 0 ? String.valueOf(appId) : name.toLowerCase(Locale.ROOT))) continue;
            out.add(new OwnedGame(appId, name, parseNumber(text(g, "hoursOnRecord")), parseNumber(text(g, "hoursPlayed"))));
        }
        return out;
    }

    // ------------------------------------------------------------ helpers

    private String get(String url) throws SteamProfileException {
        try {
            return http.get(url);
        } catch (IOException e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("HTTP 429")) {
                throw new SteamProfileException(Reason.UNAVAILABLE, "Steam is rate-limiting requests right now. Wait a minute and try again.", e);
            }
            if (msg.contains("HTTP 404")) {
                throw new SteamProfileException(Reason.NOT_FOUND, "I couldn't find that Steam profile. Check the link or custom URL name.", e);
            }
            throw new SteamProfileException(Reason.UNAVAILABLE, "Could not reach Steam (" + msg + "). Check your connection and try again.", e);
        }
    }

    private static SteamProfileException gamesPrivate() {
        return new SteamProfileException(Reason.GAMES_PRIVATE,
                "That profile doesn't list any played games. In Steam open Profile > Edit Profile > Privacy Settings and set "
                        + "\"Game details\" to Public (an account with no playtime has nothing to import).");
    }

    private static Document parseXml(String xml) throws SteamProfileException {
        String head = xml.stripLeading().toLowerCase(Locale.ROOT);
        if (head.startsWith("<!doctype html") || head.startsWith("<html")) {
            throw new SteamProfileException(Reason.UNREADABLE,
                    "Steam sent back a web page instead of profile data (it may be asking for a sign-in or blocking the request).");
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new SteamProfileException(Reason.UNREADABLE, "Steam returned something that isn't a readable profile page.", e);
        }
    }

    private static Element child(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(tag)) return e;
        }
        return null;
    }

    private static String text(Element parent, String tag) {
        Element e = child(parent, tag);
        return e == null ? "" : e.getTextContent().trim();
    }

    /** Steam prints hours like "1,234.5"; blank or unreadable means 0. */
    static double parseNumber(String s) {
        if (s == null) return 0;
        String t = s.replace(",", "").trim();
        if (t.isEmpty()) return 0;
        try {
            double d = Double.parseDouble(t);
            return Double.isFinite(d) && d > 0 ? d : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

}
