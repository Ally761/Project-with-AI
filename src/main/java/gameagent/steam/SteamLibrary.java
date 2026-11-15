package gameagent.steam;

import java.util.List;

import gameagent.model.OwnedGame;

/**
 * What a public Steam profile shows: who it is and which games it lists, with playtime.
 * {@code complete} is false when the source only exposes part of the library (e.g. the most-played list).
 */
public record SteamLibrary(String steamId, String personaName, List<OwnedGame> games, boolean complete) {}
