package cscc43.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Represents a portfolio row.
 */
public record Portfolio(long id, long ownerId, String name, BigDecimal cashBalance, OffsetDateTime createdAt) {
}
