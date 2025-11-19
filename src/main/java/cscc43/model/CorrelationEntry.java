package cscc43.model;

import java.math.BigDecimal;

/**
 * Represents correlation and covariance between two securities.
 */
public record CorrelationEntry(String symbolA, String symbolB, BigDecimal covariance, BigDecimal correlation) {
}
