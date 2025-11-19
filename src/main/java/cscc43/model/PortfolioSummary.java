package cscc43.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Aggregated view of a portfolio including cash and holdings value.
 */
public record PortfolioSummary(Portfolio portfolio, List<PortfolioPosition> positions, BigDecimal holdingsMarketValue,
                               BigDecimal totalValue) {
}
