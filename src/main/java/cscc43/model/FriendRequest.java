package cscc43.model;

import java.time.OffsetDateTime;

/**
 * Represents a friend request between two users.
 */
public record FriendRequest(long id, long requesterId, long receiverId, FriendRequestStatus status,
                            OffsetDateTime createdAt, OffsetDateTime respondedAt) {
}
