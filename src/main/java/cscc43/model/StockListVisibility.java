package cscc43.model;

/**
 * Visibility values aligned with the stock_lists table.
 */
public enum StockListVisibility {
    PRIVATE,
    FRIENDS,
    PUBLIC;

    public static StockListVisibility fromDatabase(String dbValue) {
        return StockListVisibility.valueOf(dbValue.toUpperCase());
    }

    public String toDatabase() {
        return name().toLowerCase();
    }
}
