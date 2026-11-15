package gameagent.util;

import java.io.IOException;

/** Tiny GET-only port so network access can be replaced by a stub in tests. */
public interface HttpFetcher {
    String get(String url) throws IOException;
}
