package cscc43.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Represents a historical closing price entry for charting.
 */
public record PriceHistoryPoint(LocalDate day, BigDecimal close) {
}
