package cscc43.service;

import cscc43.db.Database;
import cscc43.db.DatabaseException;
import cscc43.model.CorrelationEntry;
import cscc43.model.PortfolioStatistics;
import cscc43.model.PortfolioPosition;
import cscc43.model.PriceHistoryPoint;
import cscc43.model.PricePrediction;
import cscc43.model.SecurityStatistic;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Provides statistical analytics leveraging SQL aggregate functions and simple predictive models.
 */
public final class AnalyticsService {

    private static final MathContext PREDICTION_CONTEXT = new MathContext(8, RoundingMode.HALF_UP);

    private final Database database;
    private final PortfolioService portfolioService;
    private final StockListService stockListService;
    private final PriceService priceService;

    public AnalyticsService(Database database, PortfolioService portfolioService,
                            StockListService stockListService, PriceService priceService) {
        this.database = Objects.requireNonNull(database, "database");
        this.portfolioService = Objects.requireNonNull(portfolioService, "portfolioService");
        this.stockListService = Objects.requireNonNull(stockListService, "stockListService");
        this.priceService = Objects.requireNonNull(priceService, "priceService");
    }

    public PortfolioStatistics portfolioStatistics(long ownerId, long portfolioId, int lookbackDays,
                                                   String marketSymbol) {
        List<PortfolioPosition> holdings = portfolioService.holdings(ownerId, portfolioId);
        List<String> symbols = holdings.stream().map(PortfolioPosition::symbol).toList();
        return statisticsForSymbols(symbols, lookbackDays, marketSymbol);
    }

    public PortfolioStatistics stockListStatistics(long userId, long listId, int lookbackDays, String marketSymbol) {
        List<String> symbols = stockListService.listSymbols(listId);
        return statisticsForSymbols(symbols, lookbackDays, marketSymbol);
    }

    public List<PriceHistoryPoint> history(String symbol, int lookbackDays) {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(lookbackDays);
        return priceService.history(symbol, start, end);
    }

    public List<PricePrediction> predictLinear(String symbol, int horizonDays) {
        List<PriceHistoryPoint> history = history(symbol, 365);
        if (history.size() < 5) {
            throw new IllegalArgumentException("Not enough historical data to produce a prediction");
        }
        int n = history.size();
        BigDecimal sumX = BigDecimal.ZERO;
        BigDecimal sumY = BigDecimal.ZERO;
        BigDecimal sumXY = BigDecimal.ZERO;
        BigDecimal sumXX = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            BigDecimal x = BigDecimal.valueOf(i + 1L);
            BigDecimal y = history.get(i).close();
            sumX = sumX.add(x);
            sumY = sumY.add(y);
            sumXY = sumXY.add(x.multiply(y, PREDICTION_CONTEXT));
            sumXX = sumXX.add(x.multiply(x, PREDICTION_CONTEXT));
        }
        BigDecimal nBD = BigDecimal.valueOf(n);
        BigDecimal slopeNumerator = nBD.multiply(sumXY, PREDICTION_CONTEXT)
            .subtract(sumX.multiply(sumY, PREDICTION_CONTEXT));
        BigDecimal slopeDenominator = nBD.multiply(sumXX, PREDICTION_CONTEXT)
            .subtract(sumX.multiply(sumX, PREDICTION_CONTEXT));
        if (slopeDenominator.signum() == 0) {
            slopeDenominator = BigDecimal.ONE;
        }
        BigDecimal slope = slopeNumerator.divide(slopeDenominator, PREDICTION_CONTEXT);
        BigDecimal intercept = sumY.subtract(slope.multiply(sumX, PREDICTION_CONTEXT), PREDICTION_CONTEXT)
            .divide(nBD, PREDICTION_CONTEXT);
        int lastIndex = n;
        List<PricePrediction> predictions = new ArrayList<>(horizonDays);
        LocalDate lastDay = history.get(history.size() - 1).day();
        for (int i = 1; i <= horizonDays; i++) {
            BigDecimal x = BigDecimal.valueOf(lastIndex + i);
            BigDecimal predicted = intercept.add(slope.multiply(x, PREDICTION_CONTEXT));
            predictions.add(new PricePrediction(lastDay.plusDays(i), predicted.max(BigDecimal.ZERO)));
        }
        return predictions;
    }

    private PortfolioStatistics statisticsForSymbols(List<String> symbols, int lookbackDays, String marketSymbol) {
        if (symbols.isEmpty()) {
            return new PortfolioStatistics(List.of(), List.of());
        }
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(Math.max(lookbackDays, 1));
        try (Connection connection = database.getConnection()) {
            Array symbolsArray = connection.createArrayOf("text", symbols.stream().map(String::toUpperCase).toArray());
            List<SecurityStatistic> stats = fetchSecurityStats(connection, symbolsArray, start, end, marketSymbol);
            List<CorrelationEntry> correlations = fetchCorrelations(connection, symbolsArray, start, end);
            return new PortfolioStatistics(stats, correlations);
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    private List<SecurityStatistic> fetchSecurityStats(Connection connection, Array symbolsArray,
                                                        LocalDate start, LocalDate end, String marketSymbol)
        throws SQLException {
        String sql = "with price as ("
            + "  select symbol, day, close from unified_prices"
            + "  where symbol = any(?) and day between ? and ?"
            + "), close_stats as ("
            + "  select symbol, avg(close) as avg_close, stddev_pop(close) as volatility"
            + "  from price group by symbol"
            + "), returns as ("
            + "  select symbol, day, (close - lag(close) over (partition by symbol order by day)) "
            + "    / nullif(lag(close) over (partition by symbol order by day), 0) as ret"
            + "  from price"
            + "), returns_filtered as (select symbol, day, ret from returns where ret is not null),"
            + " market_price as (select day, close from unified_prices where symbol = upper(?) and day between ? and ?),"
            + " market_returns as ("
            + "  select day, (close - lag(close) over (order by day)) "
            + "    / nullif(lag(close) over (order by day), 0) as ret from market_price"
            + " ), market_filtered as (select day, ret from market_returns where ret is not null),"
            + " beta_stats as ("
            + "  select rf.symbol, covar_pop(rf.ret, mf.ret) as covariance, var_pop(mf.ret) as market_variance"
            + "  from returns_filtered rf join market_filtered mf on mf.day = rf.day"
            + "  group by rf.symbol"
            + ")"
            + " select cs.symbol, cs.avg_close, cs.volatility," 
            + " case when cs.avg_close = 0 then null else cs.volatility / cs.avg_close end as cov,"
            + " case when bs.market_variance is null or bs.market_variance = 0 then null"
            + "      else bs.covariance / bs.market_variance end as beta"
            + " from close_stats cs left join beta_stats bs on bs.symbol = cs.symbol";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setArray(1, symbolsArray);
            statement.setObject(2, start);
            statement.setObject(3, end);
            statement.setString(4, marketSymbol);
            statement.setObject(5, start);
            statement.setObject(6, end);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<SecurityStatistic> stats = new ArrayList<>();
                while (resultSet.next()) {
                    stats.add(new SecurityStatistic(
                        resultSet.getString("symbol"),
                        resultSet.getBigDecimal("avg_close"),
                        resultSet.getBigDecimal("volatility"),
                        resultSet.getBigDecimal("cov"),
                        resultSet.getBigDecimal("beta")));
                }
                return stats;
            }
        }
    }

    private List<CorrelationEntry> fetchCorrelations(Connection connection, Array symbolsArray, LocalDate start,
                                                     LocalDate end) throws SQLException {
        String sql = "with price as ("
            + "  select symbol, day, close from unified_prices"
            + "  where symbol = any(?) and day between ? and ?"
            + "), returns as ("
            + "  select symbol, day, (close - lag(close) over (partition by symbol order by day)) "
            + "    / nullif(lag(close) over (partition by symbol order by day), 0) as ret"
            + "  from price"
            + "), returns_filtered as (select symbol, day, ret from returns where ret is not null)"
            + " select a.symbol as symbol_a, b.symbol as symbol_b," 
            + " covar_pop(a.ret, b.ret) as covariance, corr(a.ret, b.ret) as correlation"
            + " from returns_filtered a join returns_filtered b on a.day = b.day"
            + " where a.symbol <= b.symbol"
            + " group by a.symbol, b.symbol";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setArray(1, symbolsArray);
            statement.setObject(2, start);
            statement.setObject(3, end);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<CorrelationEntry> entries = new ArrayList<>();
                while (resultSet.next()) {
                    entries.add(new CorrelationEntry(
                        resultSet.getString("symbol_a"),
                        resultSet.getString("symbol_b"),
                        resultSet.getBigDecimal("covariance"),
                        resultSet.getBigDecimal("correlation")));
                }
                return entries;
            }
        }
    }
}
