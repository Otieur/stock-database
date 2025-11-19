package cscc43.model;

import java.math.BigDecimal;

/**
 * Represents a symbol entry in a stock list.
 */
public record StockListItem(String symbol, BigDecimal shares) {
}
