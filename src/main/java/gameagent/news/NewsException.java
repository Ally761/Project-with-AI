package gameagent.news;

public class NewsException extends Exception {
    public NewsException(String message) {
        super(message);
    }

    public NewsException(String message, Throwable cause) {
        super(message, cause);
    }
}
