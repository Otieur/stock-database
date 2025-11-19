package cscc43.model;

import java.time.OffsetDateTime;

/**
 * Represents an established friendship.
 */
public record Friendship(long user1Id, long user2Id, OffsetDateTime sinceAt, OffsetDateTime deletedAt) {

    public long other(long userId) {
        if (userId == user1Id) {
            return user2Id;
        }
        if (userId == user2Id) {
            return user1Id;
        }
        throw new IllegalArgumentException("User " + userId + " is not part of this friendship");
    }
}
