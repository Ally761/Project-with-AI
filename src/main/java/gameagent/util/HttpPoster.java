package gameagent.util;

import java.io.IOException;
import java.util.Map;

/** Tiny POST port (the counterpart of {@link HttpFetcher}) so services that need POST can be tested with a stub. */
public interface HttpPoster {
    /** @return the response body of a 2xx reply; non-2xx replies throw {@link HttpStatusException} */
    String post(String url, Map<String, String> headers, String body) throws IOException;

    /** A non-success HTTP status. The body is kept for diagnosis but may contain details, so callers decide what to show. */
    class HttpStatusException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int status;

        public HttpStatusException(int status, String host) {
            super("HTTP " + status + " from " + host);
            this.status = status;
        }

        public int status() { return status; }
    }
}
