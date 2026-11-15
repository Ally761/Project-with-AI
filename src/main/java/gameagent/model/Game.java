package gameagent.model;

import java.util.List;

/** A video game. {@code verified} means it was found in a real data source (not just named by the LLM). */
public record Game(String name, long steamAppId, String description, List<String> genres,
                   String price, String releaseDate, String url, boolean verified) {

    public static Game unverified(String name) {
        return new Game(name, 0, "", List.of(), "", "", "", false);
    }

    public Game withDetails(String description, List<String> genres, String price, String releaseDate) {
        return new Game(name, steamAppId, description, genres, price, releaseDate, url, verified);
    }
}
