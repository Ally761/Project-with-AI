package gameagent.profile;

import java.io.IOException;

/** Persistence port for the user profile. */
public interface ProfileRepository {
    UserProfile load();

    void save(UserProfile profile) throws IOException;
}
