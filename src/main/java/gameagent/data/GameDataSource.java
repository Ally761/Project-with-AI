package gameagent.data;

import java.util.List;
import java.util.Optional;

import gameagent.model.Game;

/**
 * Port to a catalogue of real games. Used to ground the LLM: titles it suggests must exist here.
 * Implementations adapt a third-party API (see {@link SteamStoreAdapter}).
 */
public interface GameDataSource {
    /** Free-text search; results have basic fields only. */
    List<Game> search(String term, int limit) throws DataSourceException;

    /** Best match for an exact title, with full details, or empty if the game cannot be found. */
    Optional<Game> findByName(String name) throws DataSourceException;

    /**
     * Full details for a game whose store id is already known (e.g. from a Steam library).
     * Empty when the id is not a game in the catalogue.
     */
    Optional<Game> findByAppId(long appId, String name) throws DataSourceException;

    /** Currently popular games (top sellers). */
    List<Game> trending(int limit) throws DataSourceException;
}
