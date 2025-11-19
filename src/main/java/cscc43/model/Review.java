package cscc43.model;

import java.time.OffsetDateTime;

/**
 * Represents a stock list review.
 */
public record Review(long reviewerId, long listId, String body, OffsetDateTime createdAt,
                     OffsetDateTime updatedAt, OffsetDateTime deletedAt) {
}
