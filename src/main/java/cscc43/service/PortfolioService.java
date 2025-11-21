package cscc43.service;

import cscc43.db.Database;
import cscc43.db.DatabaseException;
import cscc43.model.Portfolio;
import cscc43.model.PortfolioPosition;
import cscc43.model.PortfolioSummary;
import cscc43.model.TradeSide;
import cscc43.model.TransactionKind;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Business logic for portfolios and holdings.
 */
public final class PortfolioService {

    private final Database database;
    private final StockService stockService;

    public PortfolioService(Database database, StockService stockService) {
        this.database = Objects.requireNonNull(database, "database");
        this.stockService = Objects.requireNonNull(stockService, "stockService");
    }

    public Portfolio createPortfolio(long ownerId, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Portfolio name cannot be empty");
        }
        return database.inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "insert into portfolios (owner_id, name) values (?, ?) returning portfolio_id, owner_id, name, cash_balance, created_at")) {
                statement.setLong(1, ownerId);
                statement.setString(2, name.trim());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new DatabaseException("Failed to create portfolio");
                    }
                    return mapPortfolio(resultSet);
                }
            }
        });
    }

    public List<Portfolio> listPortfolios(long ownerId) {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "select portfolio_id, owner_id, name, cash_balance, created_at from portfolios where owner_id = ? order by created_at")) {
            statement.setLong(1, ownerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Portfolio> portfolios = new ArrayList<>();
                while (resultSet.next()) {
                    portfolios.add(mapPortfolio(resultSet));
                }
                return portfolios;
            }
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    public Portfolio deposit(long ownerId, long portfolioId, BigDecimal amount, TransactionKind kind) {
        ensurePositive(amount, "amount");
        if (kind != TransactionKind.DEPOSIT && kind != TransactionKind.DIVIDEND && kind != TransactionKind.INTEREST
            && kind != TransactionKind.ADJUSTMENT) {
            throw new IllegalArgumentException("Unsupported transaction kind for deposit");
        }
        return database.inTransaction(connection -> {
            lockOwnedPortfolio(connection, ownerId, portfolioId);
            updateCashAndInsertTransaction(connection, portfolioId, amount, kind);
            return refreshPortfolio(connection, portfolioId);
        });
    }

    public Portfolio withdraw(long ownerId, long portfolioId, BigDecimal amount, TransactionKind kind) {
        ensurePositive(amount, "amount");
        if (kind != TransactionKind.WITHDRAW && kind != TransactionKind.FEE && kind != TransactionKind.ADJUSTMENT) {
            throw new IllegalArgumentException("Unsupported transaction kind for withdraw");
        }
        return database.inTransaction(connection -> {
            Portfolio portfolio = lockOwnedPortfolio(connection, ownerId, portfolioId);
            if (portfolio.cashBalance().compareTo(amount) < 0) {
                throw new IllegalArgumentException("Insufficient cash balance");
            }
            updateCashAndInsertTransaction(connection, portfolioId, amount.negate(), kind);
            return refreshPortfolio(connection, portfolioId);
        });
    }

    public Portfolio buyStock(long ownerId, long portfolioId, String symbol, BigDecimal shares, BigDecimal price)
        throws SQLException {
        ensurePositive(shares, "shares");
        ensurePositive(price, "price");
        BigDecimal cost = shares.multiply(price).setScale(4, RoundingMode.HALF_UP);
        return database.inTransaction(connection -> {
            Portfolio portfolio = lockOwnedPortfolio(connection, ownerId, portfolioId);
            if (portfolio.cashBalance().compareTo(cost) < 0) {
                throw new IllegalArgumentException("Insufficient cash to execute trade");
            }
            stockService.ensureStockExists(connection, symbol, null);
            insertTrade(connection, portfolioId, symbol, shares, price, TradeSide.BUY);
            upsertHolding(connection, portfolioId, symbol, shares, TradeSide.BUY);
            applyCashDelta(connection, portfolioId, cost.negate());
            return refreshPortfolio(connection, portfolioId);
        });
    }

    public Portfolio sellStock(long ownerId, long portfolioId, String symbol, BigDecimal shares, BigDecimal price)
        throws SQLException {
        ensurePositive(shares, "shares");
        ensurePositive(price, "price");
        BigDecimal proceeds = shares.multiply(price).setScale(4, RoundingMode.HALF_UP);
        return database.inTransaction(connection -> {
            lockOwnedPortfolio(connection, ownerId, portfolioId);
            BigDecimal existing = findHoldingShares(connection, portfolioId, symbol);
            if (existing.compareTo(shares) < 0) {
                throw new IllegalArgumentException("Not enough shares to sell");
            }
            stockService.ensureStockExists(connection, symbol, null);
            insertTrade(connection, portfolioId, symbol, shares, price, TradeSide.SELL);
            upsertHolding(connection, portfolioId, symbol, shares, TradeSide.SELL);
            applyCashDelta(connection, portfolioId, proceeds);
            return refreshPortfolio(connection, portfolioId);
        });
    }

    public PortfolioSummary summary(long ownerId, long portfolioId) {
        try (Connection connection = database.getConnection()) {
            Portfolio portfolio = fetchOwnedPortfolio(connection, ownerId, portfolioId)
                .orElseThrow(() -> new IllegalArgumentException("Portfolio not found"));
            List<PortfolioPosition> positions = holdings(connection, portfolioId);
            Map<String, BigDecimal> prices = stockService.latestClosePrices(
                positions.stream().map(PortfolioPosition::symbol).toList());
            List<PortfolioPosition> enriched = new ArrayList<>();
            BigDecimal holdingsMarketValue = BigDecimal.ZERO;
            for (PortfolioPosition position : positions) {
                BigDecimal lastClose = prices.get(position.symbol());
                BigDecimal marketValue = null;
                if (lastClose != null) {
                    marketValue = lastClose.multiply(position.shares()).setScale(4, RoundingMode.HALF_UP);
                    holdingsMarketValue = holdingsMarketValue.add(marketValue);
                }
                enriched.add(new PortfolioPosition(
                    position.symbol(),
                    position.shares(),
                    Optional.ofNullable(lastClose),
                    Optional.ofNullable(marketValue)));
            }
            BigDecimal totalValue = portfolio.cashBalance().add(holdingsMarketValue);
            return new PortfolioSummary(portfolio, enriched, holdingsMarketValue, totalValue);
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    public List<PortfolioPosition> holdings(long ownerId, long portfolioId) {
        try (Connection connection = database.getConnection()) {
            fetchOwnedPortfolio(connection, ownerId, portfolioId)
                .orElseThrow(() -> new IllegalArgumentException("Portfolio not found"));
            return holdings(connection, portfolioId);
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    private List<PortfolioPosition> holdings(Connection connection, long portfolioId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select symbol, shares from portfolio_holdings where portfolio_id = ? order by symbol")) {
            statement.setLong(1, portfolioId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<PortfolioPosition> positions = new ArrayList<>();
                while (resultSet.next()) {
                    String symbol = resultSet.getString("symbol");
                    BigDecimal shares = resultSet.getBigDecimal("shares");
                    positions.add(new PortfolioPosition(symbol, shares, Optional.empty(), Optional.empty()));
                }
                return positions;
            }
        }
    }

    private void applyCashDelta(Connection connection, long portfolioId, BigDecimal delta) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "update portfolios set cash_balance = cash_balance + ? where portfolio_id = ?")) {
            statement.setBigDecimal(1, delta);
            statement.setLong(2, portfolioId);
            statement.executeUpdate();
        }
    }

    private void updateCashAndInsertTransaction(Connection connection, long portfolioId, BigDecimal amount,
                                                TransactionKind kind) throws SQLException {
        try (PreparedStatement insertTxn = connection.prepareStatement(
            "insert into cash_transactions (portfolio_id, txn_kind, amount) values (?, ?, ?)")) {
            insertTxn.setLong(1, portfolioId);
            insertTxn.setString(2, kind.name());
            insertTxn.setBigDecimal(3, amount);
            insertTxn.executeUpdate();
        }
        applyCashDelta(connection, portfolioId, amount);
    }

    private void insertTrade(Connection connection, long portfolioId, String symbol, BigDecimal shares,
                             BigDecimal price, TradeSide side) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "insert into trades (portfolio_id, symbol, shares, side, price) values (?, upper(?), ?, ?, ?)")) {
            statement.setLong(1, portfolioId);
            statement.setString(2, symbol);
            statement.setBigDecimal(3, shares);
            statement.setString(4, side.name());
            statement.setBigDecimal(5, price);
            statement.executeUpdate();
        }
    }

    private void upsertHolding(Connection connection, long portfolioId, String symbol, BigDecimal shares,
                               TradeSide side) throws SQLException {
        if (side == TradeSide.BUY) {
            try (PreparedStatement statement = connection.prepareStatement(
                "insert into portfolio_holdings (portfolio_id, symbol, shares) values (?, upper(?), ?) "
                    + "on conflict (portfolio_id, symbol) do update set shares = portfolio_holdings.shares + excluded.shares")) {
                statement.setLong(1, portfolioId);
                statement.setString(2, symbol);
                statement.setBigDecimal(3, shares);
                statement.executeUpdate();
            }
        } else {
            try (PreparedStatement statement = connection.prepareStatement(
                "update portfolio_holdings set shares = shares - ? where portfolio_id = ? and symbol = upper(?)")) {
                statement.setBigDecimal(1, shares);
                statement.setLong(2, portfolioId);
                statement.setString(3, symbol);
                if (statement.executeUpdate() == 0) {
                    throw new IllegalStateException("Holding not found during sell");
                }
            }
        }
        try (PreparedStatement cleanup = connection.prepareStatement(
            "delete from portfolio_holdings where portfolio_id = ? and symbol = upper(?) and shares <= 0")) {
            cleanup.setLong(1, portfolioId);
            cleanup.setString(2, symbol);
            cleanup.executeUpdate();
        }
    }

    private BigDecimal findHoldingShares(Connection connection, long portfolioId, String symbol) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select shares from portfolio_holdings where portfolio_id = ? and symbol = upper(?)")) {
            statement.setLong(1, portfolioId);
            statement.setString(2, symbol);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getBigDecimal("shares");
                }
                return BigDecimal.ZERO;
            }
        }
    }

    private Portfolio refreshPortfolio(Connection connection, long portfolioId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select portfolio_id, owner_id, name, cash_balance, created_at from portfolios where portfolio_id = ?")) {
            statement.setLong(1, portfolioId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new DatabaseException("Portfolio vanished during update");
                }
                return mapPortfolio(resultSet);
            }
        }
    }

    private Portfolio lockOwnedPortfolio(Connection connection, long ownerId, long portfolioId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select portfolio_id, owner_id, name, cash_balance, created_at from portfolios "
                + "where owner_id = ? and portfolio_id = ? for update")) {
            statement.setLong(1, ownerId);
            statement.setLong(2, portfolioId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("Portfolio not found");
                }
                return mapPortfolio(resultSet);
            }
        }
    }

    private Optional<Portfolio> fetchOwnedPortfolio(Connection connection, long ownerId, long portfolioId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select portfolio_id, owner_id, name, cash_balance, created_at from portfolios "
                + "where owner_id = ? and portfolio_id = ?")) {
            statement.setLong(1, ownerId);
            statement.setLong(2, portfolioId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(mapPortfolio(resultSet));
                }
                return Optional.empty();
            }
        }
    }

    private static Portfolio mapPortfolio(ResultSet resultSet) throws SQLException {
        long id = resultSet.getLong("portfolio_id");
        long ownerId = resultSet.getLong("owner_id");
        String name = resultSet.getString("name");
        BigDecimal cash = resultSet.getBigDecimal("cash_balance");
        OffsetDateTime createdAt = resultSet.getObject("created_at", OffsetDateTime.class);
        return new Portfolio(id, ownerId, name, cash, createdAt);
    }

    private static void ensurePositive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
