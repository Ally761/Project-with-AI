package gameagent.news;

import java.time.Instant;
import java.util.List;

import gameagent.model.NewsItem;

/** Items to show plus how trustworthy/fresh they are. */
public record NewsResult(List<NewsItem> items, boolean fromCache, Instant fetchedAt, boolean aiRanked, List<String> warnings) {}
