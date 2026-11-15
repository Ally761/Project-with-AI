package gameagent.util;

/** Thrown when text cannot be parsed as JSON. */
public class JsonException extends Exception {
    public JsonException(String message) {
        super(message);
    }
}
