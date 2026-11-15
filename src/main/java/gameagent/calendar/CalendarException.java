package gameagent.calendar;

/** The calendar could not be built or changed; the message tells the user what to do next. */
public class CalendarException extends Exception {
    private static final long serialVersionUID = 1L;

    public CalendarException(String message) {
        super(message);
    }

    public CalendarException(String message, Throwable cause) {
        super(message, cause);
    }
}
