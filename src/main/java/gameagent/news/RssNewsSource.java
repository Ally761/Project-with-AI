package gameagent.news;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import gameagent.model.NewsItem;
import gameagent.util.HttpFetcher;

/** Adapter from RSS 2.0 / Atom feeds to {@link NewsItem}s. */
public class RssNewsSource implements NewsSource {
    private final String name;
    private final String url;
    private final HttpFetcher http;

    public RssNewsSource(String name, String url, HttpFetcher http) {
        this.name = name;
        this.url = url;
        this.http = http;
    }

    @Override
    public String name() { return name; }

    @Override
    public List<NewsItem> fetch() throws NewsException {
        try {
            return parse(http.get(url), name);
        } catch (IOException e) {
            throw new NewsException(name + ": " + e.getMessage(), e);
        }
    }

    static List<NewsItem> parse(String xml, String sourceName) throws NewsException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            trySet(f, "http://xml.org/sax/features/external-general-entities", false);
            trySet(f, "http://xml.org/sax/features/external-parameter-entities", false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            Document doc = b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            List<NewsItem> out = new ArrayList<>();
            NodeList items = doc.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) out.add(toItem((Element) items.item(i), sourceName, false));
            NodeList entries = doc.getElementsByTagName("entry");
            for (int i = 0; i < entries.getLength(); i++) out.add(toItem((Element) entries.item(i), sourceName, true));
            out.removeIf(n -> n.title().isBlank() || n.link().isBlank());
            return out;
        } catch (Exception e) {
            throw new NewsException(sourceName + ": could not parse feed (" + e.getMessage() + ")", e);
        }
    }

    private static void trySet(DocumentBuilderFactory f, String feature, boolean value) {
        try {
            f.setFeature(feature, value);
        } catch (Exception ignored) {
            // parser does not support it; secure processing is still on
        }
    }

    private static NewsItem toItem(Element e, String source, boolean atom) {
        String title = clean(text(e, "title"));
        String link = atom ? atomLink(e) : text(e, "link").trim();
        String summary = clean(firstNonBlank(text(e, "description"), text(e, "summary"), text(e, "content:encoded"), text(e, "content")));
        if (summary.length() > 300) summary = summary.substring(0, 297) + "...";
        Instant when = parseDate(firstNonBlank(text(e, "pubDate"), text(e, "published"), text(e, "updated"), text(e, "dc:date")));
        return new NewsItem(title, link, source, when, summary, "");
    }

    private static String atomLink(Element e) {
        NodeList links = e.getElementsByTagName("link");
        for (int i = 0; i < links.getLength(); i++) {
            Element l = (Element) links.item(i);
            String rel = l.getAttribute("rel");
            if ((rel.isEmpty() || rel.equals("alternate")) && !l.getAttribute("href").isEmpty()) return l.getAttribute("href");
        }
        return "";
    }

    /** Text of the first direct child with this tag name ("" if none). */
    private static String text(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && el.getTagName().equals(tag)) return el.getTextContent();
        }
        return "";
    }

    private static String firstNonBlank(String... s) {
        for (String x : s) if (x != null && !x.isBlank()) return x;
        return "";
    }

    static String clean(String html) {
        return html.replaceAll("<[^>]*>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replaceAll("\\s+", " ").trim();
    }

    static Instant parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        try {
            return ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception ignored) { }
        try {
            return OffsetDateTime.parse(t).toInstant();
        } catch (Exception ignored) { }
        try {
            return Instant.parse(t);
        } catch (Exception ignored) { }
        return null;
    }
}
