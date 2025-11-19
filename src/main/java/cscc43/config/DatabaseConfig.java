package cscc43.config;

import java.util.Map;

/**
 * Simple immutable configuration holder for database settings.
 */
public final class DatabaseConfig {

    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final int maximumPoolSize;

    private DatabaseConfig(String jdbcUrl, String user, String password, int maximumPoolSize) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
        this.maximumPoolSize = maximumPoolSize;
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    public int maximumPoolSize() {
        return maximumPoolSize;
    }

    public static DatabaseConfig fromEnv() {
        Map<String, String> env = System.getenv();
        String url = env.getOrDefault("DB_URL", "jdbc:postgresql://127.0.0.1:5433/cscc43");
        String username = env.getOrDefault("DB_USER", "cscc43");
        String pwd = env.getOrDefault("DB_PASSWORD", "cscc43");
        int poolSize = parsePoolSize(env.get("DB_POOL_SIZE"));
        return new DatabaseConfig(url, username, pwd, poolSize);
    }

    private static int parsePoolSize(String raw) {
        if (raw == null || raw.isBlank()) {
            return 8;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : 8;
        } catch (NumberFormatException ignored) {
            return 8;
        }
    }

    @Override
    public String toString() {
        return "DatabaseConfig{"
            + "jdbcUrl='" + jdbcUrl + '\''
            + ", user='" + user + '\''
            + ", maximumPoolSize=" + maximumPoolSize
            + '}';
    }
}
