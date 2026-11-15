package gameagent.model;

import java.time.Instant;

public record NewsItem(String title, String link, String source, Instant published, String summary, String why) {
    public NewsItem withWhy(String why) {
        return new NewsItem(title, link, source, published, summary, why);
    }
}
