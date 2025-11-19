package cscc43.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Represents a single daily price observation.
 */
public record DailyPrice(String symbol, LocalDate day, BigDecimal open, BigDecimal high, BigDecimal low,
                         BigDecimal close, long volume) {
}
