package cscc43.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import cscc43.config.DatabaseConfig;

import java.io.Closeable;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Lightweight wrapper around HikariCP with helpers for transaction handling.
 */
public final class Database implements Closeable {

    private final HikariDataSource dataSource;

    public Database(DatabaseConfig config) {
        Objects.requireNonNull(config, "config");
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.jdbcUrl());
        hikariConfig.setUsername(config.user());
        hikariConfig.setPassword(config.password());
        hikariConfig.setMaximumPoolSize(config.maximumPoolSize());
        hikariConfig.setAutoCommit(false);
        hikariConfig.setPoolName("cscc43-pool");
        hikariConfig.setConnectionInitSql("set search_path to app, public");
        this.dataSource = new HikariDataSource(hikariConfig);
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public <T> T inTransaction(SqlFunction<Connection, T> callback) {
        Objects.requireNonNull(callback, "callback");
        try (Connection connection = getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = callback.apply(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof SQLException sqlException) {
                    throw new DatabaseException(sqlException);
                }
                throw new DatabaseException("Error during transaction", exception);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }

    @FunctionalInterface
    public interface SqlFunction<T, R> {
        R apply(T connection) throws Exception;
    }
}
