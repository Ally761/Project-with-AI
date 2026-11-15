package gameagent.steam;

/**
 * Port to wherever a user's public Steam library can be read from. The implementation
 * ({@link SteamCommunityAdapter}) uses public profile pages; no API key or login is needed.
 */
public interface SteamProfileSource {
    SteamLibrary fetchLibrary(SteamProfileRef ref) throws SteamProfileException;
}
