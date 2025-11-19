package cscc43.model;

import java.time.OffsetDateTime;

/**
 * Represents an explicit friend share for a stock list.
 */
public record StockListShare(long listId, long sharedWith, OffsetDateTime sharedAt) {
}
