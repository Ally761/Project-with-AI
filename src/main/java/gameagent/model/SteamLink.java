package gameagent.model;

import java.time.Instant;

/** The public Steam profile the user linked. Only public information is ever read; no login or key is involved. */
public record SteamLink(String steamId, String personaName, String profileUrl, Instant linkedAt) {}
