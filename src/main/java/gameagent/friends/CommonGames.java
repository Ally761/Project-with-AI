package gameagent.friends;

import java.util.List;

import gameagent.model.OwnedGame;

/** What the user and one friend have in common, and what the friend plays that the user does not own. */
public record CommonGames(String friend, List<String> shared, List<OwnedGame> onlyTheirs, boolean complete) {}
