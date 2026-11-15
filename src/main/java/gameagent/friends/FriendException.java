package gameagent.friends;

/** A friend could not be found or changed; the message tells the user what to do. */
public class FriendException extends Exception {
    private static final long serialVersionUID = 1L;

    public FriendException(String message) {
        super(message);
    }
}
