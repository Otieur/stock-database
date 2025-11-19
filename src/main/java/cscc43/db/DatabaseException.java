package cscc43.db;

/**
 * Runtime wrapper around SQL exceptions for simpler error handling in the CLI.
 */
public final class DatabaseException extends RuntimeException {

    public DatabaseException(String message) {
        super(message);
    }

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }

    public DatabaseException(Throwable cause) {
        super(cause);
    }
}
