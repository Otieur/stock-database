package cscc43.service;

import cscc43.db.Database;
import cscc43.db.DatabaseException;
import cscc43.model.FriendRequest;
import cscc43.model.FriendRequestStatus;
import cscc43.model.Friendship;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Manages friend requests and friendships.
 */
public final class FriendService {

    private static final Duration REQUEST_COOLDOWN = Duration.ofMinutes(5);

    private final Database database;

    public FriendService(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public FriendRequest sendRequest(long requesterId, long receiverId) {
        if (requesterId == receiverId) {
            throw new IllegalArgumentException("Cannot send a friend request to yourself");
        }
        return database.inTransaction(connection -> {
            ensureUsersNotAlreadyFriends(connection, requesterId, receiverId);
            ensureNoRecentBlock(connection, requesterId, receiverId);
            try (PreparedStatement statement = connection.prepareStatement(
                "insert into friend_requests (requester_id, receiver_id, status) values (?, ?, 'pending') "
                    + "returning request_id, requester_id, receiver_id, status, created_at, responded_at")) {
                statement.setLong(1, requesterId);
                statement.setLong(2, receiverId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new DatabaseException("Unable to create friend request");
                    }
                    return mapRequest(resultSet);
                }
            }
        });
    }

    public void cancelRequest(long requesterId, long requestId) {
        database.inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "update friend_requests set status = 'cancelled', responded_at = now() "
                    + "where request_id = ? and requester_id = ? and status = 'pending'")) {
                statement.setLong(1, requestId);
                statement.setLong(2, requesterId);
                if (statement.executeUpdate() == 0) {
                    throw new IllegalArgumentException("No pending request to cancel");
                }
            }
            return null;
        });
    }

    public void respondToRequest(long receiverId, long requestId, boolean accept) {
        database.inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "select request_id, requester_id, receiver_id, status from friend_requests "
                    + "where request_id = ? for update")) {
                statement.setLong(1, requestId);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("Friend request not found");
                    }
                    if (rs.getLong("receiver_id") != receiverId) {
                        throw new IllegalArgumentException("Cannot respond to a request not addressed to you");
                    }
                    String status = rs.getString("status");
                    if (!"pending".equals(status)) {
                        throw new IllegalArgumentException("Request already processed");
                    }
                    long requesterId = rs.getLong("requester_id");
                    updateRequestStatus(connection, requestId, accept ? "accepted" : "rejected");
                    if (accept) {
                        upsertFriendship(connection, requesterId, receiverId);
                    }
                }
            }
            return null;
        });
    }

    public void removeFriend(long userId, long friendId) {
        database.inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "delete from friendships where (user1_id = ? and user2_id = ?) or (user1_id = ? and user2_id = ?)")) {
                statement.setLong(1, Math.min(userId, friendId));
                statement.setLong(2, Math.max(userId, friendId));
                statement.setLong(3, Math.min(userId, friendId));
                statement.setLong(4, Math.max(userId, friendId));
                if (statement.executeUpdate() == 0) {
                    throw new IllegalArgumentException("Users are not friends");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                "insert into friend_requests (requester_id, receiver_id, status, created_at, responded_at) "
                    + "values (?, ?, 'cancelled', now(), now())")) {
                statement.setLong(1, userId);
                statement.setLong(2, friendId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public List<FriendRequest> incomingRequests(long userId) {
        return queryRequests("receiver_id = ?", userId);
    }

    public List<FriendRequest> outgoingRequests(long userId) {
        return queryRequests("requester_id = ?", userId);
    }

    public List<Friendship> friendships(long userId) {
        String sql = "select user1_id, user2_id, since_at, deleted_at from friendships "
            + "where (user1_id = ? or user2_id = ?) order by since_at desc";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setLong(2, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Friendship> friendships = new ArrayList<>();
                while (resultSet.next()) {
                    friendships.add(mapFriendship(resultSet));
                }
                return friendships;
            }
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    private void ensureUsersNotAlreadyFriends(Connection connection, long requesterId, long receiverId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select 1 from friendships where user1_id = ? and user2_id = ?")) {
            statement.setLong(1, Math.min(requesterId, receiverId));
            statement.setLong(2, Math.max(requesterId, receiverId));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    throw new IllegalArgumentException("Users are already friends");
                }
            }
        }
    }

    private void ensureNoRecentBlock(Connection connection, long requesterId, long receiverId) throws SQLException {
        String sql = "select status, responded_at, created_at from friend_requests "
            + "where (requester_id = ? and receiver_id = ?) or (requester_id = ? and receiver_id = ?) "
            + "order by greatest(coalesce(responded_at, created_at), created_at) desc limit 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, requesterId);
            statement.setLong(2, receiverId);
            statement.setLong(3, receiverId);
            statement.setLong(4, requesterId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    String status = resultSet.getString("status");
                    OffsetDateTime timestamp = resultSet.getObject("responded_at", OffsetDateTime.class);
                    if (timestamp == null) {
                        timestamp = resultSet.getObject("created_at", OffsetDateTime.class);
                    }
                    if ("pending".equals(status)) {
                        throw new IllegalArgumentException("There is already a pending request between these users");
                    }
                    if ("rejected".equals(status) || "cancelled".equals(status)) {
                        OffsetDateTime cooldownEnd = timestamp.plus(REQUEST_COOLDOWN);
                        if (OffsetDateTime.now().isBefore(cooldownEnd)) {
                            throw new IllegalArgumentException("Must wait "
                                + REQUEST_COOLDOWN.toMinutes() + " minutes before resending a request");
                        }
                    }
                }
            }
        }
    }

    private void updateRequestStatus(Connection connection, long requestId, String newStatus) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
            "update friend_requests set status = ?, responded_at = now() where request_id = ?")) {
            update.setString(1, newStatus);
            update.setLong(2, requestId);
            update.executeUpdate();
        }
    }

    private void upsertFriendship(Connection connection, long userA, long userB) throws SQLException {
        long user1 = Math.min(userA, userB);
        long user2 = Math.max(userA, userB);
        try (PreparedStatement insert = connection.prepareStatement(
            "insert into friendships (user1_id, user2_id, since_at) values (?, ?, now()) "
                + "on conflict (user1_id, user2_id) do update set since_at = excluded.since_at, deleted_at = null")) {
            insert.setLong(1, user1);
            insert.setLong(2, user2);
            insert.executeUpdate();
        }
    }

    private List<FriendRequest> queryRequests(String condition, long userId) {
        String sql = "select request_id, requester_id, receiver_id, status, created_at, responded_at "
            + "from friend_requests where " + condition + " order by created_at desc";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<FriendRequest> requests = new ArrayList<>();
                while (resultSet.next()) {
                    requests.add(mapRequest(resultSet));
                }
                return requests;
            }
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    private static FriendRequest mapRequest(ResultSet resultSet) throws SQLException {
        long id = resultSet.getLong("request_id");
        long requesterId = resultSet.getLong("requester_id");
        long receiverId = resultSet.getLong("receiver_id");
        FriendRequestStatus status = FriendRequestStatus.fromDatabase(resultSet.getString("status"));
        OffsetDateTime createdAt = resultSet.getObject("created_at", OffsetDateTime.class);
        OffsetDateTime respondedAt = resultSet.getObject("responded_at", OffsetDateTime.class);
        return new FriendRequest(id, requesterId, receiverId, status, createdAt, respondedAt);
    }

    private static Friendship mapFriendship(ResultSet resultSet) throws SQLException {
        long user1 = resultSet.getLong("user1_id");
        long user2 = resultSet.getLong("user2_id");
        OffsetDateTime sinceAt = resultSet.getObject("since_at", OffsetDateTime.class);
        OffsetDateTime deletedAt = resultSet.getObject("deleted_at", OffsetDateTime.class);
        return new Friendship(user1, user2, sinceAt, deletedAt);
    }
}
