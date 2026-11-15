package gameagent.news;

import java.util.List;

import gameagent.model.NewsItem;

/** A provider of gaming news headlines. Implementations adapt a concrete feed/API. */
public interface NewsSource {
    String name();

    List<NewsItem> fetch() throws NewsException;
}
