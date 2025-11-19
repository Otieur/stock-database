package cscc43.model;

import java.util.List;

/**
 * Aggregate of per-security statistics and correlation entries.
 */
public record PortfolioStatistics(List<SecurityStatistic> securities, List<CorrelationEntry> correlations) {
}
