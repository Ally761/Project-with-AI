package gameagent.playtime;

public class PlaytimeException extends Exception {
    private static final long serialVersionUID = 1L;

    public PlaytimeException(String message) {
        super(message);
    }

    public PlaytimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
