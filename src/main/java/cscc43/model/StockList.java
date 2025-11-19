package cscc43.model;

import java.time.OffsetDateTime;

/**
 * Represents a stock list header row.
 */
public record StockList(long id, long ownerId, String name, StockListVisibility visibility,
                        OffsetDateTime createdAt) {
}
