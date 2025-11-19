package cscc43.model;

import java.time.OffsetDateTime;

/**
 * Represents a system user.
 */
public record User(long id, String name, String email, OffsetDateTime createdAt) {
}
