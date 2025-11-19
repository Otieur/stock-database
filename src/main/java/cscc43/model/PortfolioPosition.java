package cscc43.model;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Holds an individual holding summary within a portfolio.
 */
public record PortfolioPosition(String symbol, BigDecimal shares, Optional<BigDecimal> lastClose, Optional<BigDecimal> marketValue) {

    public PortfolioPosition {
        if (shares == null) {
            throw new IllegalArgumentException("shares cannot be null");
        }
        if (shares.signum() < 0) {
            throw new IllegalArgumentException("shares cannot be negative");
        }
    }
}
