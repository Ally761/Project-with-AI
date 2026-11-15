package gameagent.model;

import java.util.List;

/** What a Steam link or refresh did, for showing to the user. */
public record SteamLinkResult(SteamLink link, int libraryCount, double totalHours, List<OwnedGame> topPlayed,
                              List<String> applied, List<String> warnings) {}
