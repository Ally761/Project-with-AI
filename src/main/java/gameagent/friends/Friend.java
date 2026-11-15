package gameagent.friends;

import java.time.Instant;
import java.util.List;

import gameagent.model.OwnedGame;

/** A friend whose public Steam profile the user gave. {@code games} is what that profile shows (usually the most played). */
public record Friend(String steamId, String name, String profileUrl, List<OwnedGame> games, Instant updated) {
    public boolean plays(String gameName) {
        for (OwnedGame g : games) if (gameagent.util.Names.norm(g.name()).equals(gameagent.util.Names.norm(gameName))) return true;
        return false;
    }
}
