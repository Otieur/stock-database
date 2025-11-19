package cscc43.model;

/**
 * Enum representation of friend request status values defined in the database check constraint.
 */
public enum FriendRequestStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    CANCELLED;

    public static FriendRequestStatus fromDatabase(String value) {
        return FriendRequestStatus.valueOf(value.toUpperCase());
    }

    public String toDatabase() {
        return name().toLowerCase();
    }
}
