package cscc43.service;

import cscc43.db.Database;
import cscc43.db.DatabaseException;
import cscc43.model.StockList;
import cscc43.model.StockListAccess;
import cscc43.model.StockListItem;
import cscc43.model.StockListVisibility;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Service for managing stock lists, visibility and shares.
 */
public final class StockListService {

    private final Database database;
    private final StockService stockService;

    public StockListService(Database database, StockService stockService) {
        this.database = Objects.requireNonNull(database, "database");
        this.stockService = Objects.requireNonNull(stockService, "stockService");
    }

    public StockList createList(long ownerId, String name, StockListVisibility visibility) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("List name cannot be empty");
        }
        return database.inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "insert into stock_lists (owner_id, name, visibility) values (?, ?, ?) "
                    + "returning list_id, owner_id, name, visibility, created_at")) {
                statement.setLong(1, ownerId);
                statement.setString(2, name.trim());
                statement.setString(3, visibility.toDatabase());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new DatabaseException("Failed to create stock list");
                    }
                    return mapList(resultSet);
                }
            }
        });
    }

    public StockList updateVisibility(long ownerId, long listId, StockListVisibility visibility) {
        return database.inTransaction(connection -> {
            StockList existing = requireOwnership(connection, ownerId, listId);
            try (PreparedStatement statement = connection.prepareStatement(
                "update stock_lists set visibility = ? where list_id = ?")) {
                statement.setString(1, visibility.toDatabase());
                statement.setLong(2, listId);
                statement.executeUpdate();
            }
            return new StockList(existing.id(), existing.ownerId(), existing.name(), visibility, existing.createdAt());
        });
    }

    public void deleteList(long ownerId, long listId) {
        database.inTransaction(connection -> {
            requireOwnership(connection, ownerId, listId);
            try (PreparedStatement statement = connection.prepareStatement(
                "delete from stock_lists where list_id = ?")) {
                statement.setLong(1, listId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void addItem(long ownerId, long listId, String symbol, BigDecimal shares, String companyName)
        throws SQLException {
        if (shares == null || shares.signum() <= 0) {
            throw new IllegalArgumentException("Shares must be positive");
        }
        database.inTransaction(connection -> {
            requireOwnership(connection, ownerId, listId);
            stockService.ensureStockExists(connection, symbol, companyName);
            try (PreparedStatement statement = connection.prepareStatement(
                "insert into stock_list_items (list_id, symbol, shares) values (?, upper(?), ?) "
                    + "on conflict (list_id, symbol) do update set shares = excluded.shares")) {
                statement.setLong(1, listId);
                statement.setString(2, symbol);
                statement.setBigDecimal(3, shares);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void removeItem(long ownerId, long listId, String symbol) {
        database.inTransaction(connection -> {
            requireOwnership(connection, ownerId, listId);
            try (PreparedStatement statement = connection.prepareStatement(
                "delete from stock_list_items where list_id = ? and symbol = upper(?)")) {
                statement.setLong(1, listId);
                statement.setString(2, symbol);
                if (statement.executeUpdate() == 0) {
                    throw new IllegalArgumentException("Symbol not present in list");
                }
            }
            return null;
        });
    }

    public List<StockListItem> listItems(long listId) {
        String sql = "select symbol, shares from stock_list_items where list_id = ? order by symbol";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, listId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<StockListItem> items = new ArrayList<>();
                while (resultSet.next()) {
                    items.add(new StockListItem(resultSet.getString("symbol"),
                        resultSet.getBigDecimal("shares")));
                }
                return items;
            }
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    public void shareWithFriend(long ownerId, long listId, long friendId) {
        if (ownerId == friendId) {
            throw new IllegalArgumentException("Cannot share a list with yourself");
        }
        database.inTransaction(connection -> {
            requireOwnership(connection, ownerId, listId);
            ensureFriendship(connection, ownerId, friendId);
            try (PreparedStatement statement = connection.prepareStatement(
                "insert into stock_list_shares (list_id, shared_with) values (?, ?) on conflict do nothing")) {
                statement.setLong(1, listId);
                statement.setLong(2, friendId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void unshareWithFriend(long ownerId, long listId, long friendId) {
        database.inTransaction(connection -> {
            requireOwnership(connection, ownerId, listId);
            try (PreparedStatement statement = connection.prepareStatement(
                "delete from stock_list_shares where list_id = ? and shared_with = ?")) {
                statement.setLong(1, listId);
                statement.setLong(2, friendId);
                if (statement.executeUpdate() == 0) {
                    throw new IllegalArgumentException("List is not shared with provided user");
                }
            }
            return null;
        });
    }

    public List<StockListAccess> accessibleLists(long userId) {
        String sql = "with available as ("
            + "  select sl.list_id, sl.owner_id, sl.name, sl.visibility, sl.created_at, 'owner'::text as access"
            + "  from stock_lists sl where sl.owner_id = ?"
            + "  union all"
            + "  select sl.list_id, sl.owner_id, sl.name, sl.visibility, sl.created_at, 'shared'"
            + "  from stock_lists sl join stock_list_shares s on s.list_id = sl.list_id"
            + "  where s.shared_with = ?"
            + "  union all"
            + "  select sl.list_id, sl.owner_id, sl.name, sl.visibility, sl.created_at, 'friends'"
            + "  from stock_lists sl join friendships f on f.user1_id = least(sl.owner_id, ?)"
            + "    and f.user2_id = greatest(sl.owner_id, ?)"
            + "  where sl.visibility = 'friends' and sl.owner_id <> ?"
            + "  union all"
            + "  select sl.list_id, sl.owner_id, sl.name, sl.visibility, sl.created_at, 'public'"
            + "  from stock_lists sl where sl.visibility = 'public' and sl.owner_id <> ?"
            + ") select * from available order by created_at desc";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setLong(2, userId);
            statement.setLong(3, userId);
            statement.setLong(4, userId);
            statement.setLong(5, userId);
            statement.setLong(6, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<StockListAccess> lists = new ArrayList<>();
                Set<Long> seen = new HashSet<>();
                while (resultSet.next()) {
                    long id = resultSet.getLong("list_id");
                    if (seen.add(id)) {
                        lists.add(new StockListAccess(mapList(resultSet), resultSet.getString("access")));
                    }
                }
                return lists;
            }
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    public Optional<StockListAccess> findAccessibleList(long userId, long listId) {
        String sql = "select list_id, owner_id, name, visibility, created_at, access from ("
            + "  select sl.list_id, sl.owner_id, sl.name, sl.visibility, sl.created_at, 'owner'::text as access"
            + "  from stock_lists sl where sl.owner_id = ? and sl.list_id = ?"
            + "  union all"
            + "  select sl.list_id, sl.owner_id, sl.name, sl.visibility, sl.created_at, 'shared'"
            + "  from stock_lists sl join stock_list_shares s on s.list_id = sl.list_id"
            + "  where s.shared_with = ? and sl.list_id = ?"
            + "  union all"
            + "  select sl.list_id, sl.owner_id, sl.name, sl.visibility, sl.created_at, 'friends'"
            + "  from stock_lists sl join friendships f on f.user1_id = least(sl.owner_id, ?)"
            + "    and f.user2_id = greatest(sl.owner_id, ?)"
            + "  where sl.visibility = 'friends' and sl.list_id = ?"
            + "  union all"
            + "  select sl.list_id, sl.owner_id, sl.name, sl.visibility, sl.created_at, 'public'"
            + "  from stock_lists sl where sl.visibility = 'public' and sl.list_id = ?"
            + ") as eligible limit 1";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setLong(2, listId);
            statement.setLong(3, userId);
            statement.setLong(4, listId);
            statement.setLong(5, userId);
            statement.setLong(6, userId);
            statement.setLong(7, listId);
            statement.setLong(8, listId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(new StockListAccess(mapList(resultSet), resultSet.getString("access")));
                }
                return Optional.empty();
            }
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    public List<Long> sharedWith(long ownerId, long listId) {
        String sql = "select shared_with from stock_list_shares where list_id = ? order by shared_with";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            requireOwnership(connection, ownerId, listId);
            statement.setLong(1, listId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Long> users = new ArrayList<>();
                while (resultSet.next()) {
                    users.add(resultSet.getLong("shared_with"));
                }
                return users;
            }
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    public List<String> listSymbols(long listId) {
        String sql = "select symbol from stock_list_items where list_id = ? order by symbol";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, listId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> symbols = new ArrayList<>();
                while (resultSet.next()) {
                    symbols.add(resultSet.getString("symbol"));
                }
                return symbols;
            }
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    public List<StockList> ownerLists(long ownerId) {
        String sql = "select list_id, owner_id, name, visibility, created_at from stock_lists where owner_id = ?"
            + " order by created_at desc";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, ownerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<StockList> lists = new ArrayList<>();
                while (resultSet.next()) {
                    lists.add(mapList(resultSet));
                }
                return lists;
            }
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    public void bulkAddItems(long ownerId, long listId, List<StockListItem> items) throws SQLException {
        if (items.isEmpty()) {
            return;
        }
        database.inTransaction(connection -> {
            requireOwnership(connection, ownerId, listId);
            try (PreparedStatement statement = connection.prepareStatement(
                "insert into stock_list_items (list_id, symbol, shares) values (?, ?, ?) "
                    + "on conflict (list_id, symbol) do update set shares = excluded.shares")) {
                for (StockListItem item : items) {
                    if (item.shares().signum() <= 0) {
                        throw new IllegalArgumentException("Shares must be positive for symbol " + item.symbol());
                    }
                    stockService.ensureStockExists(connection, item.symbol(), null);
                    statement.setLong(1, listId);
                    statement.setString(2, item.symbol());
                    statement.setBigDecimal(3, item.shares());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        });
    }

    private StockList requireOwnership(Connection connection, long ownerId, long listId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select list_id, owner_id, name, visibility, created_at from stock_lists where list_id = ? for update")) {
            statement.setLong(1, listId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("Stock list not found");
                }
                long recordOwner = resultSet.getLong("owner_id");
                if (recordOwner != ownerId) {
                    throw new IllegalArgumentException("You do not own this stock list");
                }
                return mapList(resultSet);
            }
        }
    }

    private void ensureFriendship(Connection connection, long userId, long friendId) throws SQLException {
        long user1 = Math.min(userId, friendId);
        long user2 = Math.max(userId, friendId);
        try (PreparedStatement statement = connection.prepareStatement(
            "select 1 from friendships where user1_id = ? and user2_id = ?")) {
            statement.setLong(1, user1);
            statement.setLong(2, user2);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("Users are not friends");
                }
            }
        }
    }

    private static StockList mapList(ResultSet resultSet) throws SQLException {
        long id = resultSet.getLong("list_id");
        long ownerId = resultSet.getLong("owner_id");
        String name = resultSet.getString("name");
        StockListVisibility visibility = StockListVisibility.fromDatabase(resultSet.getString("visibility"));
        OffsetDateTime createdAt = resultSet.getObject("created_at", OffsetDateTime.class);
        return new StockList(id, ownerId, name, visibility, createdAt);
    }
}
