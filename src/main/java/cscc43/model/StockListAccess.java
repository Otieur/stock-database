package cscc43.model;

/**
 * Wrapper describing how a user can access a stock list.
 */
public record StockListAccess(StockList list, String accessibility) {
}
