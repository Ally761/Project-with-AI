package gameagent.cli;

/** Thrown by the quit command to leave the main loop. */
public class QuitException extends RuntimeException {
    public QuitException() {
        super("quit", null, false, false);
    }
}
