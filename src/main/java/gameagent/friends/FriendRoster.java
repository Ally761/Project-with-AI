package gameagent.friends;

import java.util.List;

/** Read-only view of the user's friends. Strategies depend on this, not on how friends are fetched or stored. */
public interface FriendRoster {
    List<Friend> friends();

    static FriendRoster empty() {
        return List::of;
    }
}
