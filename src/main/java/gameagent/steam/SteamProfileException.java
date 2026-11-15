package gameagent.steam;

/** A Steam profile could not be linked. The message is written for the user and says what to do about it. */
public class SteamProfileException extends Exception {
    public enum Reason { INVALID_INPUT, NOT_FOUND, PROFILE_PRIVATE, GAMES_PRIVATE, UNAVAILABLE, UNREADABLE }

    private final Reason reason;

    public SteamProfileException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public SteamProfileException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
