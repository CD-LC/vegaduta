package com.agenticai.core.dbauth;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * A DataSource whose password is a fresh token from a {@link DbTokenSupplier} on every {@link
 * #getConnection()}. HikariCP wraps it ({@code setDataSource}) and calls it once per new physical
 * connection (never for a borrow of an idle one), which is exactly when a token is checked: an open
 * connection is not re-authenticated, so a pooled connection outliving its token keeps working
 * until the server closes it. Flyway, the tenant schema migrator and JPA all receive the pool, so
 * they all log in this way.
 */
final class TokenDataSource extends AbstractDataSource {

    /** Opens a physical connection; the JDBC driver manager in production, a fake in tests. */
    @FunctionalInterface
    interface ConnectionFactory {
        Connection open(String url, Properties info) throws SQLException;
    }

    private final String url;
    private final String username;
    private final DbTokenSupplier tokens;
    private final ConnectionFactory factory;

    TokenDataSource(
            String url, String username, DbTokenSupplier tokens, ConnectionFactory factory) {
        this.url = url;
        this.username = username;
        this.tokens = tokens;
        this.factory = factory;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return getConnection(username, null);
    }

    /** The password argument is ignored: the token is the password. */
    @Override
    public Connection getConnection(String user, String ignoredPassword) throws SQLException {
        String token;
        try {
            token = tokens.password();
        } catch (DbAuthException e) {
            throw new SQLException(e.getMessage(), "28000", e);
        }
        Properties info = new Properties();
        info.setProperty("user", user == null ? username : user);
        info.setProperty("password", token);
        return factory.open(url, info);
    }
}
