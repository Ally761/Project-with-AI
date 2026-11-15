package gameagent.cli;

import java.io.PrintStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import gameagent.model.NewsItem;
import gameagent.model.Recommendation;

/** Plain-text rendering of results (kept separate from the logic so a GUI can render the same data its own way). */
final class Printer {
    private static final int WIDTH = 92;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private Printer() {}

    static void recommendations(PrintStream out, List<Recommendation> recs) {
        int i = 1;
        for (Recommendation r : recs) {
            var g = r.game();
            StringBuilder head = new StringBuilder(i++ + ". " + g.name());
            if (r.label() != null) head.append("  [").append(r.label()).append("]");
            if (r.matchScore() >= 0) head.append("  (match ").append(r.matchScore()).append("%)");
            if (!g.verified()) head.append("  (unverified)");
            out.println(head);
            StringBuilder meta = new StringBuilder();
            if (!g.genres().isEmpty()) meta.append("Genres: ").append(String.join(", ", g.genres()));
            if (!g.price().isBlank()) meta.append(meta.length() > 0 ? " | " : "").append("Price: ").append(g.price());
            if (!g.releaseDate().isBlank()) meta.append(meta.length() > 0 ? " | " : "").append("Released: ").append(g.releaseDate());
            if (meta.length() > 0) wrap(out, meta.toString(), "   ");
            if (!r.reason().isBlank()) wrap(out, "Why: " + r.reason(), "   ");
            if (!g.url().isBlank()) out.println("   " + g.url());
            out.println();
        }
    }

    static void news(PrintStream out, List<NewsItem> items) {
        int i = 1;
        for (NewsItem n : items) {
            out.println(i++ + ". " + n.title());
            String when = n.published() == null ? "" : " - " + DATE.format(n.published());
            out.println("   " + n.source() + when);
            if (!n.why().isBlank()) wrap(out, "For you: " + n.why(), "   ");
            out.println("   " + n.link());
            out.println();
        }
    }

    static void wrap(PrintStream out, String text, String indent) {
        StringBuilder line = new StringBuilder(indent);
        for (String word : text.split("\\s+")) {
            if (line.length() + word.length() + 1 > WIDTH && line.length() > indent.length()) {
                out.println(line);
                line = new StringBuilder(indent);
            }
            if (line.length() > indent.length()) line.append(' ');
            line.append(word);
        }
        if (line.length() > indent.length()) out.println(line);
    }
}
