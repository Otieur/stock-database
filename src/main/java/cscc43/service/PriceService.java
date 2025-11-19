package cscc43.service;

import cscc43.db.Database;
import cscc43.db.DatabaseException;
import cscc43.model.DailyPrice;
import cscc43.model.PriceHistoryPoint;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Handles price ingestion and retrieval.
 */
public final class PriceService {

    private static final int BATCH_SIZE = 500;

    private final Database database;
    private final StockService stockService;

    public PriceService(Database database, StockService stockService) {
        this.database = Objects.requireNonNull(database, "database");
        this.stockService = Objects.requireNonNull(stockService, "stockService");
    }

    public void insertNewPrice(DailyPrice price, Long addedBy) {
        database.inTransaction(connection -> {
            stockService.ensureStockExists(connection, price.symbol(), null);
            try (PreparedStatement statement = connection.prepareStatement(
                "insert into daily_price_new (symbol, day, open, high, low, close, volume, added_by) "
                    + "values (upper(?), ?, ?, ?, ?, ?, ?, ?) on conflict (symbol, day) do update set "
                    + "open = excluded.open, high = excluded.high, low = excluded.low, close = excluded.close, "
                    + "volume = excluded.volume, added_by = excluded.added_by, added_at = now()")) {
                bindPrice(statement, price);
                if (addedBy == null) {
                    statement.setNull(8, java.sql.Types.BIGINT);
                } else {
                    statement.setLong(8, addedBy);
                }
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void loadHistoricCsv(Path csvPath) throws IOException {
        if (!Files.exists(csvPath)) {
            throw new IllegalArgumentException("CSV file does not exist: " + csvPath);
        }
        try (BufferedReader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                throw new IllegalArgumentException("CSV file is empty");
            }
            List<CsvRow> batch = new ArrayList<>(BATCH_SIZE);
            Set<String> ensuredSymbols = new HashSet<>();
            String line;
            while ((line = reader.readLine()) != null) {
                CsvRow row = CsvRow.parse(line);
                batch.add(row);
                if (batch.size() >= BATCH_SIZE) {
                    flushBatch(batch, ensuredSymbols);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                flushBatch(batch, ensuredSymbols);
            }
        }
    }

    public List<PriceHistoryPoint> history(String symbol, LocalDate start, LocalDate end) {
        String sql = "select day, close from unified_prices where symbol = upper(?) and day between ? and ? order by day";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setObject(2, start);
            statement.setObject(3, end);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<PriceHistoryPoint> history = new ArrayList<>();
                while (resultSet.next()) {
                    history.add(new PriceHistoryPoint(resultSet.getObject("day", LocalDate.class),
                        resultSet.getBigDecimal("close")));
                }
                return history;
            }
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    private void flushBatch(List<CsvRow> batch, Set<String> ensuredSymbols) {
        database.inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "insert into daily_price_historic (symbol, day, open, high, low, close, volume)"
                    + " values (upper(?), ?, ?, ?, ?, ?, ?) on conflict (symbol, day) do nothing")) {
                for (CsvRow row : batch) {
                    if (ensuredSymbols.add(row.symbol())) {
                        stockService.ensureStockExists(connection, row.symbol(), null);
                    }
                    statement.setString(1, row.symbol());
                    statement.setObject(2, row.day());
                    setBigDecimalOrNull(statement, 3, row.open());
                    setBigDecimalOrNull(statement, 4, row.high());
                    setBigDecimalOrNull(statement, 5, row.low());
                    setBigDecimalOrNull(statement, 6, row.close());
                    if (row.volume() == null) {
                        statement.setNull(7, java.sql.Types.BIGINT);
                    } else {
                        statement.setLong(7, row.volume());
                    }
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        });
    }

    private static void bindPrice(PreparedStatement statement, DailyPrice price) throws SQLException {
        statement.setString(1, price.symbol());
        statement.setObject(2, price.day());
        statement.setBigDecimal(3, price.open());
        statement.setBigDecimal(4, price.high());
        statement.setBigDecimal(5, price.low());
        statement.setBigDecimal(6, price.close());
        statement.setLong(7, price.volume());
    }

    private static void setBigDecimalOrNull(PreparedStatement statement, int parameterIndex, BigDecimal value)
        throws SQLException {
        if (value == null) {
            statement.setNull(parameterIndex, java.sql.Types.NUMERIC);
        } else {
            statement.setBigDecimal(parameterIndex, value);
        }
    }

    private record CsvRow(String symbol, LocalDate day, BigDecimal open, BigDecimal high, BigDecimal low,
                          BigDecimal close, Long volume) {

        private static CsvRow parse(String line) {
            String[] parts = line.split(",");
            if (parts.length < 7) {
                throw new IllegalArgumentException("Invalid CSV row: " + line);
            }
            LocalDate date = LocalDate.parse(parts[0].trim());
            BigDecimal open = parseDecimal(parts[1]);
            BigDecimal high = parseDecimal(parts[2]);
            BigDecimal low = parseDecimal(parts[3]);
            BigDecimal close = parseDecimal(parts[4]);
            if (close == null) {
                throw new IllegalArgumentException("Missing close price in row: " + line);
            }
            Long volume = parseLong(parts[5]);
            String symbol = parts[6].trim();
            return new CsvRow(symbol, date, open, high, low, close, volume);
        }

        private static BigDecimal parseDecimal(String raw) {
            if (raw == null) {
                return null;
            }
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            return new BigDecimal(trimmed);
        }

        private static Long parseLong(String raw) {
            if (raw == null) {
                return null;
            }
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            return Long.parseLong(trimmed);
        }
    }
}
