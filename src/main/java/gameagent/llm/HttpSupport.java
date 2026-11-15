package gameagent.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import gameagent.util.Json;

/** Shared POST-JSON plumbing for the HTTP based LLM clients. */
final class HttpSupport {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private HttpSupport() {}

    record Response(int status, String body) {}

    static Response postJson(String url, Map<String, String> headers, String body) throws LLMException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(b::header);
        try {
            HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(r.statusCode(), r.body());
        } catch (IOException e) {
            throw new LLMException("Network error contacting LLM: " + e.getMessage(), 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LLMException("Interrupted while waiting for LLM", 0, e);
        }
    }

    /** Pulls "error.message" out of an API error body when present. */
    static String errorMessage(String body) {
        try {
            Map<String, Object> o = Json.parseObject(body);
            Map<String, Object> err = Json.obj(o, "error");
            String msg = Json.str(err, "message");
            if (msg != null) return msg;
        } catch (Exception ignored) {
            // fall through
        }
        return body == null ? "" : body.substring(0, Math.min(200, body.length()));
    }
}
