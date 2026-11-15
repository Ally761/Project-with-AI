package gameagent.profile;

/** Observer interface: services that depend on the profile register with {@link ProfileManager}. */
public interface ProfileListener {
    void onProfileChanged(ProfileEvent event);
}
