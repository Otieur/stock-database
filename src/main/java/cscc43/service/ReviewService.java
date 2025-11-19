package cscc43.service;

import cscc43.db.Database;
import cscc43.db.DatabaseException;
import cscc43.model.Review;
import cscc43.model.StockListAccess;
import cscc43.model.StockListVisibility;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Handles review creation and retrieval with visibility checks.
 */
public final class ReviewService {

    private final Database database;
    private final StockListService stockListService;

    public ReviewService(Database database, StockListService stockListService) {
        this.database = Objects.requireNonNull(database, "database");
        this.stockListService = Objects.requireNonNull(stockListService, "stockListService");
    }

    public Review upsertReview(long reviewerId, long listId, String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Review body cannot be empty");
        }
        if (body.length() > 4000) {
            throw new IllegalArgumentException("Review body exceeds 4000 characters");
        }
        StockListAccess access = stockListService.findAccessibleList(reviewerId, listId)
            .orElseThrow(() -> new IllegalArgumentException("Stock list not accessible"));
        if (access.list().visibility() == StockListVisibility.PRIVATE && reviewerId != access.list().ownerId()) {
            throw new IllegalArgumentException("Private lists can only be reviewed by their owner");
        }
        return database.inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "insert into reviews (reviewer_id, list_id, body) values (?, ?, ?) "
                    + "on conflict (reviewer_id, list_id) do update set body = excluded.body, updated_at = now() "
                    + "returning reviewer_id, list_id, body, created_at, updated_at, deleted_at")) {
                statement.setLong(1, reviewerId);
                statement.setLong(2, listId);
                statement.setString(3, body);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new DatabaseException("Failed to upsert review");
                    }
                    return mapReview(resultSet);
                }
            }
        });
    }

    public void deleteReview(long requesterId, long listId, long reviewerId) {
        database.inTransaction(connection -> {
            StockListAccess access = stockListService.findAccessibleList(requesterId, listId)
                .orElseThrow(() -> new IllegalArgumentException("Stock list not accessible"));
            if (access.list().ownerId() != requesterId && reviewerId != requesterId) {
                throw new IllegalArgumentException("Only the reviewer or list owner can delete the review");
            }
            ensureReviewer(connection, reviewerId, listId);
            try (PreparedStatement statement = connection.prepareStatement(
                "delete from reviews where list_id = ? and reviewer_id = ?")) {
                statement.setLong(1, listId);
                statement.setLong(2, reviewerId);
                if (statement.executeUpdate() == 0) {
                    throw new IllegalArgumentException("No review to delete");
                }
            }
            return null;
        });
    }

    public List<Review> listReviews(long requesterId, long listId) {
        StockListAccess access = stockListService.findAccessibleList(requesterId, listId)
            .orElseThrow(() -> new IllegalArgumentException("Stock list not accessible"));
        boolean isPublic = access.list().visibility() == StockListVisibility.PUBLIC;
        boolean owner = requesterId == access.list().ownerId();
        String sql = "select reviewer_id, list_id, body, created_at, updated_at, deleted_at from reviews "
            + "where list_id = ?";
        if (!isPublic && !owner) {
            sql += " and reviewer_id = ?";
        }
        sql += " order by created_at desc";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, listId);
            if (!isPublic && !owner) {
                statement.setLong(2, requesterId);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Review> reviews = new ArrayList<>();
                while (resultSet.next()) {
                    reviews.add(mapReview(resultSet));
                }
                return reviews;
            }
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    private void ensureReviewer(Connection connection, long reviewerId, long listId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select 1 from reviews where list_id = ? and reviewer_id = ?")) {
            statement.setLong(1, listId);
            statement.setLong(2, reviewerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("Only the reviewer or list owner can delete the review");
                }
            }
        }
    }

    private static Review mapReview(ResultSet resultSet) throws SQLException {
        long reviewerId = resultSet.getLong("reviewer_id");
        long listId = resultSet.getLong("list_id");
        String body = resultSet.getString("body");
        OffsetDateTime createdAt = resultSet.getObject("created_at", OffsetDateTime.class);
        OffsetDateTime updatedAt = resultSet.getObject("updated_at", OffsetDateTime.class);
        OffsetDateTime deletedAt = resultSet.getObject("deleted_at", OffsetDateTime.class);
        return new Review(reviewerId, listId, body, createdAt, updatedAt, deletedAt);
    }
}
