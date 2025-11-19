package cscc43.service;

import cscc43.db.Database;
import cscc43.db.DatabaseException;
import cscc43.model.User;
import org.mindrot.jbcrypt.BCrypt;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * User-centric data access helpers.
 */
public final class UserService {

    private final Database database;

    public UserService(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public User register(String name, String email, String password) {
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(password, "password");
        String trimmedEmail = email.trim();
        if (trimmedEmail.isEmpty()) {
            throw new IllegalArgumentException("Email cannot be empty");
        }
        String hashed = BCrypt.hashpw(password, BCrypt.gensalt(12));
        return database.inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "insert into users (name, email, password_hash) values (?, ?, ?) "
                    + "returning user_id, name, email, created_at")) {
                statement.setString(1, name);
                statement.setString(2, trimmedEmail);
                statement.setString(3, hashed);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new DatabaseException("Failed to insert user");
                    }
                    return mapUser(resultSet);
                }
            } catch (SQLException exception) {
                if ("23505".equals(exception.getSQLState())) {
                    throw new IllegalArgumentException("Email already registered");
                }
                throw exception;
            }
        });
    }

    public User login(String email, String password) {
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(password, "password");
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "select user_id, name, email, password_hash, created_at from users where lower(email) = lower(?)")) {
            statement.setString(1, email.trim());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("Invalid credentials");
                }
                String hash = resultSet.getString("password_hash");
                if (!BCrypt.checkpw(password, hash)) {
                    throw new IllegalArgumentException("Invalid credentials");
                }
                return mapUser(resultSet);
            }
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    public List<User> list() {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "select user_id, name, email, created_at from users order by created_at")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                List<User> users = new ArrayList<>();
                while (resultSet.next()) {
                    users.add(mapUser(resultSet));
                }
                return users;
            }
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    public Optional<User> findById(long id) {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "select user_id, name, email, created_at from users where user_id = ?")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(mapUser(resultSet));
                }
                return Optional.empty();
            }
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    private static User mapUser(ResultSet resultSet) throws SQLException {
        long id = resultSet.getLong("user_id");
        String name = resultSet.getString("name");
        String email = resultSet.getString("email");
        OffsetDateTime createdAt = resultSet.getObject("created_at", OffsetDateTime.class);
        return new User(id, name, email, createdAt);
    }
}
