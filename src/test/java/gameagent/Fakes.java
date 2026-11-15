package gameagent;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import gameagent.llm.LLMClient;
import gameagent.llm.LLMException;
import gameagent.llm.LLMRequest;
import gameagent.llm.Message;
import gameagent.util.HttpFetcher;

/** Test doubles. These live in src/test only: the shipped app never uses canned responses. */
final class Fakes {
    private Fakes() {}

    /** LLM whose behaviour is a function of the request; records every request. */
    static class ScriptedLLM implements LLMClient {
        final List<LLMRequest> requests = new ArrayList<>();
        final Function<LLMRequest, String> script;
        boolean down = false;

        ScriptedLLM(Function<LLMRequest, String> script) {
            this.script = script;
        }

        @Override
        public String complete(LLMRequest r) throws LLMException {
            requests.add(r);
            if (down) throw new LLMException("simulated outage", 503);
            return script.apply(r);
        }

        @Override
        public String name() { return "fake-llm"; }
    }

    static String lastUser(LLMRequest r) {
        List<Message> m = r.messages();
        return m.get(m.size() - 1).content();
    }

    /** Behaves like the real model would for each prompt type, so whole flows can be exercised offline. */
    static String smartScript(LLMRequest r) {
        String sys = r.systemPrompt();
        String user = lastUser(r);
        if (sys.contains("You are GameAgent")) {
            if (user.startsWith("TOOL_RESULT update_preferences")) {
                return "{\"tool\":\"recommend_games\",\"args\":{\"query\":\"something like Hades\",\"count\":3},\"reply\":\"\"}";
            }
            if (user.startsWith("TOOL_RESULT recommend_games")) {
                return "{\"tool\":null,\"args\":{},\"reply\":\"Here are three picks that match your love of fast combat.\"}";
            }
            if (user.startsWith("TOOL_RESULT get_news")) {
                return "{\"tool\":null,\"reply\":\"Latest headlines above.\"}";
            }
            if (user.toLowerCase().contains("news")) {
                return "{\"tool\":\"get_news\",\"args\":{\"limit\":3},\"reply\":\"\"}";
            }
            if (user.toLowerCase().contains("hello")) {
                return "{\"tool\":null,\"reply\":\"Hi! What kind of games do you enjoy?\"}";
            }
            return "{\"tool\":\"update_preferences\",\"args\":{\"likes\":[{\"category\":\"game\",\"name\":\"Hades\"},{\"category\":\"genre\",\"name\":\"Roguelike\"}],\"notes\":[\"short sessions\"]}}";
        }
        if (sys.contains("You suggest REAL")) {
            return "{\"candidates\":[{\"name\":\"Dead Cells\",\"why\":\"fast roguelike combat\"},{\"name\":\"Hades\",\"why\":\"already known\"},"
                    + "{\"name\":\"Totally Fake Game 9000\",\"why\":\"hallucination\"},{\"name\":\"Hollow Knight\",\"why\":\"tight combat\"},"
                    + "{\"name\":\"Risk of Rain 2\",\"why\":\"co-op roguelike\"},{\"name\":\"Returnal\",\"why\":\"console exclusive roguelike\"}]}";
        }
        if (sys.contains("You choose and explain")) {
            return "{\"recommendations\":[{\"name\":\"Dead Cells\",\"score\":93,\"reason\":\"Fast combat like Hades, in short runs.\"},"
                    + "{\"name\":\"Hollow Knight\",\"score\":85,\"reason\":\"Precise combat.\"},"
                    + "{\"name\":\"Risk of Rain 2\",\"score\":80,\"reason\":\"Roguelike runs.\"},"
                    + "{\"name\":\"Not In The List\",\"score\":99,\"reason\":\"should be ignored\"}]}";
        }
        if (sys.contains("curveball roulette")) {
            return "{\"popular\":{\"name\":\"Counter-Strike 2\",\"reason\":\"Huge right now.\"},\"niche\":{\"name\":\"Rain World\",\"reason\":\"Atmospheric survival oddity.\"}}";
        }
        if (sys.contains("curate a personalised gaming news")) {
            return "{\"picks\":[{\"index\":1,\"why\":\"Roguelike news\"},{\"index\":0,\"why\":\"General interest\"},{\"index\":99,\"why\":\"bad index\"}]}";
        }
        if (sys.contains("interview a gamer")) {
            return "{\"questions\":[\"What platforms do you play on?\",\"How long are your usual play sessions?\",\"Anything you really dislike?\"]}";
        }
        if (sys.contains("convert a gamer's free-text")) {
            if (user.contains("rated")) {
                return "{\"likes\":[{\"category\":\"tag\",\"name\":\"Fast-paced combat\"}],\"dislikes\":[{\"category\":\"tag\",\"name\":\"Weak story\"}],\"remove\":[],\"notes\":[]}";
            }
            return "{\"likes\":[{\"category\":\"platform\",\"name\":\"PC\"}],\"dislikes\":[{\"category\":\"tag\",\"name\":\"Grinding\"}],\"remove\":[],\"notes\":[\"sessions around 45 minutes\"]}";
        }
        if (sys.contains("typical hours to finish")) {
            StringBuilder sb = new StringBuilder("{\"games\":[");
            boolean first = true;
            for (String line : user.split("\n")) {
                if (!line.startsWith("- ")) continue;
                String n = line.substring(2).trim();
                double h = n.equals("Hades") ? 22 : n.equals("Dead Cells") ? 18 : n.equals("Hollow Knight") ? 30 : n.equals("Celeste") ? 8 : 10;
                String vibe = n.equals("Celeste") ? "chill" : "intense";
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"name\":\"").append(n).append("\",\"hours\":").append(h).append(",\"vibes\":[\"").append(vibe).append("\",\"bogus\"]}");
            }
            return sb.append("]}").toString();
        }
        if (sys.contains("weekly time blocks")) {
            return "{\"slots\":[{\"days\":[\"mon\",\"Wednesday\"],\"start\":\"20:00\",\"end\":\"22:00\",\"vibe\":\"chill\"},{\"days\":[\"sat\"],\"start\":\"9:00\",\"end\":\"8:00\",\"vibe\":\"\"}]}";
        }
        if (sys.contains("adjust their gaming calendar")) {
            String said = user.substring(user.indexOf("The user says:")).toLowerCase();
            if (said.contains("friday")) return "{\"reset\":false,\"blockDays\":[\"fri\"],\"maxSessionMinutes\":0,\"prioritize\":[],\"exclude\":[],\"mode\":\"\"}";
            if (said.contains("hades first")) return "{\"blockDays\":[],\"maxSessionMinutes\":60,\"prioritize\":[\"Hades\"],\"exclude\":[\"Dead Cells\"],\"mode\":\"mix\"}";
            return "{}";
        }
        return "{}";
    }

    /** Pretends to be store.steampowered.com with a tiny catalogue, using the real response layout. */
    static class FakeSteam implements HttpFetcher {
        boolean down = false;
        final List<String> urls = new ArrayList<>();

        private static final String[][] GAMES = {
                // id, name, genres, price cents
                {"1145360", "Hades", "Action,Indie,RPG", "2499"},
                {"588650", "Dead Cells", "Action,Indie", "2499"},
                {"367520", "Hollow Knight", "Action,Adventure,Indie", "1499"},
                {"632360", "Risk of Rain 2", "Action,Indie", "2499"},
                {"730", "Counter-Strike 2", "Action,Free To Play", "0"},
                {"761890", "Rain World", "Action,Adventure,Indie", "2499"},
        };

        @Override
        public String get(String url) throws IOException {
            urls.add(url);
            if (down) throw new IOException("HTTP 503 from store.steampowered.com");
            if (url.contains("/api/storesearch/")) {
                String term = URLDecoder.decode(url.replaceAll(".*term=([^&]*).*", "$1"), StandardCharsets.UTF_8).toLowerCase();
                StringBuilder items = new StringBuilder();
                for (String[] g : GAMES) {
                    if (g[1].toLowerCase().contains(term) || term.contains(g[1].toLowerCase())) {
                        if (items.length() > 0) items.append(',');
                        items.append("{\"type\":\"app\",\"name\":\"").append(g[1]).append("\",\"id\":").append(g[0])
                                .append(",\"price\":{\"currency\":\"USD\",\"initial\":").append(g[3]).append(",\"final\":").append(g[3]).append("}}");
                    }
                }
                return "{\"total\":1,\"items\":[" + items + "]}";
            }
            if (url.contains("/api/appdetails")) {
                String id = url.replaceAll(".*appids=(\\d+).*", "$1");
                for (String[] g : GAMES) {
                    if (g[0].equals(id)) {
                        StringBuilder genres = new StringBuilder();
                        for (String s : g[2].split(",")) {
                            if (genres.length() > 0) genres.append(',');
                            genres.append("{\"id\":\"1\",\"description\":\"").append(s).append("\"}");
                        }
                        boolean free = g[3].equals("0");
                        return "{\"" + id + "\":{\"success\":true,\"data\":{\"name\":\"" + g[1] + "\",\"is_free\":" + free
                                + ",\"short_description\":\"A great game: " + g[1] + ".\",\"genres\":[" + genres
                                + "],\"release_date\":{\"coming_soon\":false,\"date\":\"1 Jan, 2020\"}"
                                + (free ? "" : ",\"price_overview\":{\"final\":" + g[3] + ",\"final_formatted\":\"$" + Integer.parseInt(g[3]) / 100 + "." + String.format("%02d", Integer.parseInt(g[3]) % 100) + "\"}")
                                + "}}}";
                    }
                }
                return "{\"" + id + "\":{\"success\":false}}";
            }
            if (url.contains("/api/featuredcategories")) {
                return "{\"specials\":{\"items\":[]},\"top_sellers\":{\"id\":\"cat_topsellers\",\"items\":["
                        + "{\"id\":730,\"name\":\"Counter-Strike 2\",\"final_price\":0,\"currency\":\"USD\"},"
                        + "{\"id\":1145360,\"name\":\"Hades\",\"final_price\":2499,\"currency\":\"USD\"},"
                        + "{\"id\":730,\"name\":\"Counter-Strike 2\",\"final_price\":0,\"currency\":\"USD\"}]}}";
            }
            throw new IOException("unexpected url " + url);
        }
    }

    /**
     * Pretends to be steamcommunity.com. The profile feed copies the layout Steam really serves (checked against a live
     * public profile): profile fields plus a short mostPlayedGames list. Like the real site it sends anonymous visitors
     * to the login page for the full games list, so only the profile feed is offered here.
     */
    static class FakeCommunity implements HttpFetcher {
        static final String ID = "76561198000000001";
        boolean down = false;
        boolean rateLimited = false;
        final List<String> urls = new ArrayList<>();

        static String profile(String id, String persona, String privacy, String mostPlayed) {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><profile>\n\t<steamID64>" + id + "</steamID64>\n"
                    + "\t<steamID><![CDATA[" + persona + "]]></steamID>\n\t<onlineState>offline</onlineState>\n"
                    + "\t<stateMessage><![CDATA[Offline]]></stateMessage>\n\t<privacyState>" + privacy + "</privacyState>\n"
                    + "\t<visibilityState>" + ("public".equals(privacy) ? 3 : 1) + "</visibilityState>\n"
                    + "\t<customURL><![CDATA[" + persona + "]]></customURL>\n\t<hoursPlayed2Wk>0.0</hoursPlayed2Wk>\n"
                    + "\t<summary><![CDATA[]]></summary>\n"
                    + (mostPlayed.isEmpty() ? "" : " \t<mostPlayedGames>\n" + mostPlayed + "\t</mostPlayedGames>\n") + "\n</profile>\n";
        }

        static String game(long id, String name, String total, String recent) {
            return "\t\t<mostPlayedGame>\n\t\t\t<gameName><![CDATA[" + name + "]]></gameName>\n"
                    + "\t\t\t<gameLink><![CDATA[https://steamcommunity.com/app/" + id + "]]></gameLink>\n"
                    + "\t\t\t<gameIcon><![CDATA[https://shared.example/" + id + ".jpg]]></gameIcon>\n"
                    + "\t\t\t<hoursPlayed>" + recent + "</hoursPlayed>\n\t\t\t<hoursOnRecord>" + total + "</hoursOnRecord>\n"
                    + "\t\t\t<statsName><![CDATA[" + id + "]]></statsName>\n\t\t</mostPlayedGame>\n";
        }

        static final String MOST_PLAYED = game(367520, "Hollow Knight", "1,034.2", "3.5")
                + game(1145360, "Hades", "212.5", "0.0")
                + game(504230, "Celeste", "40", "0.0")
                + game(730, "Counter-Strike 2", "12", "10")
                + game(588650, "Dead Cells", "1.5", "0.0")
                + game(431960, "Wallpaper Engine", "300", "0.1")
                + game(367520, "Hollow Knight (duplicate row)", "5", "0.0");

        @Override
        public String get(String url) throws IOException {
            urls.add(url);
            if (rateLimited) throw new IOException("HTTP 429 from steamcommunity.com");
            if (down) throw new IOException("connect timed out");
            if (url.contains("/id/hadesfan/?xml=1") || url.contains("/profiles/" + ID + "/?xml=1")) return profile(ID, "Hades Fan", "public", MOST_PLAYED);
            if (url.contains("/id/privateguy/?xml=1")) return profile("76561198000000002", "Private Guy", "private", "");
            if (url.contains("/id/hiddengames/?xml=1")) return profile("76561198000000003", "Hidden", "public", "");
            if (url.contains("/id/nobody/?xml=1")) {
                return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><response><error><![CDATA[The specified profile could not be found.]]></error></response>";
            }
            throw new IOException("HTTP 404 from steamcommunity.com");
        }
    }

    static final String RSS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel><title>Test</title>
              <item><title>Hades II gets a huge roguelike update</title><link>https://ex.com/a</link>
                <description>&lt;p&gt;The &lt;b&gt;roguelike&lt;/b&gt; sequel adds new weapons &amp;amp; bosses.&lt;/p&gt;</description>
                <pubDate>Fri, 13 Nov 2026 10:00:00 GMT</pubDate></item>
              <item><title>Racing sim announces new season</title><link>https://ex.com/b</link>
                <description>Cars go fast.</description><pubDate>Thu, 12 Nov 2026 10:00:00 GMT</pubDate></item>
              <item><title>Broken item without link</title><link></link></item>
            </channel></rss>""";

    static final String ATOM = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom"><title>Atom</title>
              <entry><title>Atom story</title><link rel="alternate" href="https://ex.com/atom1"/>
                <summary>Cozy farming sim</summary><updated>2026-11-13T09:00:00Z</updated></entry>
            </feed>""";
}
