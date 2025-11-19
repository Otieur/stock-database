package cscc43.service;

import cscc43.db.Database;
import cscc43.db.DatabaseException;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stock-related helpers for ensuring reference data exists and fetching price snapshots.
 */
public final class StockService {

    private final Database database;

    public StockService(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public void ensureStockExists(Connection connection, String symbol, String companyName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "insert into stocks (symbol, company_name) values (upper(?), ?) on conflict (symbol) do update set company_name = coalesce(excluded.company_name, stocks.company_name)")) {
            statement.setString(1, symbol);
            statement.setString(2, companyName);
            statement.executeUpdate();
        }
    }

    public Map<String, BigDecimal> latestClosePrices(List<String> symbols) {
        if (symbols.isEmpty()) {
            return Collections.emptyMap();
        }
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "select distinct on (symbol) symbol, close from unified_prices where symbol = any(?) order by symbol, day desc")) {
            Array array = connection.createArrayOf("text", symbols.stream().map(String::toUpperCase).toArray());
            statement.setArray(1, array);
            try (ResultSet resultSet = statement.executeQuery()) {
                Map<String, BigDecimal> prices = new HashMap<>();
                while (resultSet.next()) {
                    prices.put(resultSet.getString("symbol"), resultSet.getBigDecimal("close"));
                }
                return prices;
            }
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }
}
