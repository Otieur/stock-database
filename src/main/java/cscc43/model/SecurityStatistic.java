package cscc43.model;

import java.math.BigDecimal;

/**
 * Holds per-symbol risk metrics.
 */
public record SecurityStatistic(String symbol, BigDecimal averageClose, BigDecimal volatility,
                                BigDecimal coefficientOfVariation, BigDecimal beta) {
}
