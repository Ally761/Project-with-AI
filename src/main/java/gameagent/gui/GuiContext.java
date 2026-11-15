package gameagent.gui;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javax.swing.JComponent;

/**
 * What a panel needs from the window around it: background work, messages and dialogs.
 * Panels talk only to this interface (never to dialogs or threads directly), so the real window and the
 * test double are interchangeable.
 */
public interface GuiContext {
    /**
     * Runs slow work (AI or network calls) off the screen thread, then hands the result to {@code done} on the screen thread.
     * The {@code lock} components are disabled until the work ends; a failure is shown to the user instead of {@code done} running.
     */
    <T> void run(String busy, Callable<T> work, Consumer<T> done, JComponent... lock);

    /** Short one-line message in the status bar. */
    void status(String text);

    void error(String text);

    void info(String text);

    boolean confirm(String question);

    void openLink(String url);

    /** Asks all questions in one dialog. Returns the answers in order (blank allowed), or null if the user cancelled. */
    List<String> askAll(String title, List<String> questions);
}
