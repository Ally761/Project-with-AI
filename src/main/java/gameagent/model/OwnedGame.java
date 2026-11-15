package gameagent.model;

/** A game in the user's Steam library, with total playtime and playtime over the last two weeks (hours). */
public record OwnedGame(long appId, String name, double hours, double hoursRecent) {}
