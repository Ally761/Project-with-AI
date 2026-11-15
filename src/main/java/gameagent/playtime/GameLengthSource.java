package gameagent.playtime;

import java.util.Optional;

/**
 * Port: a third-party database of how long games take. {@link IgdbGameLengthSource} is one implementation;
 * {@link #none()} is used when no credentials are configured, so the rest of the code never checks for null.
 */
public interface GameLengthSource {
    String name();

    /** @return the game's figures, or empty when the source does not know the game (not an error) */
    Optional<GameLength> lookup(String gameName) throws PlaytimeException;

    /** Null Object: knows nothing. */
    static GameLengthSource none() {
        return new GameLengthSource() {
            @Override public String name() { return "none"; }
            @Override public Optional<GameLength> lookup(String gameName) { return Optional.empty(); }
        };
    }

    default boolean configured() { return !name().equals("none"); }
}
