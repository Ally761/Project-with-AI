package gameagent.profile;

import java.io.IOException;

/** Observer that persists the profile after every change. */
public class AutoSaveListener implements ProfileListener {
    private final ProfileManager manager;
    private final ProfileRepository repository;

    public AutoSaveListener(ProfileManager manager, ProfileRepository repository) {
        this.manager = manager;
        this.repository = repository;
    }

    @Override
    public void onProfileChanged(ProfileEvent event) {
        try {
            repository.save(manager.profile());
        } catch (IOException e) {
            System.err.println("Warning: could not save profile: " + e.getMessage());
        }
    }
}
