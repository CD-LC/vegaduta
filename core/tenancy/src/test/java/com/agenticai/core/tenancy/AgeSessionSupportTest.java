package com.agenticai.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Infra handoff: AGE must load for a NON-superuser role. Preloaded -> no
 * LOAD at all; not preloaded -> {@code LOAD '$libdir/plugins/age'}; plugins
 * path refused -> legacy {@code LOAD 'age'}; and {@code ag_catalog} goes on
 * (and comes back off) {@code search_path}.
 */
class AgeSessionSupportTest {

    private Connection connection;
    private Statement statement;
    private PreparedStatement setSearchPath;

    @BeforeEach
    void setUp() throws SQLException {
        AgeSessionSupport.resetCachedPreloadStateForTests();
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        setSearchPath = mock(PreparedStatement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(AgeSessionSupport.SET_SEARCH_PATH)).thenReturn(setSearchPath);
        stubScalar(AgeSessionSupport.SHOW_SHARED_PRELOAD, "");
        stubScalar(AgeSessionSupport.SHOW_SESSION_PRELOAD, "");
        stubScalar(AgeSessionSupport.CURRENT_SEARCH_PATH, "\"$user\", public");
    }

    private void stubScalar(String sql, String value) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn(value);
        when(statement.executeQuery(sql)).thenReturn(rs);
    }

    @Test
    void preloadedAgeIsNeverLoadedAgain() throws SQLException {
        stubScalar(AgeSessionSupport.SHOW_SHARED_PRELOAD, "pg_stat_statements,age");

        AgeSessionSupport.loadIfNeeded(connection);

        verify(statement, never()).execute(anyString());
    }

    @Test
    void sessionPreloadWithAQuotedPluginsPathAlsoCounts() throws SQLException {
        stubScalar(AgeSessionSupport.SHOW_SESSION_PRELOAD, "'$libdir/plugins/age'");

        AgeSessionSupport.loadIfNeeded(connection);

        verify(statement, never()).execute(anyString());
    }

    @Test
    void notPreloadedUsesTheNonSuperuserPluginsPath() throws SQLException {
        AgeSessionSupport.loadIfNeeded(connection);

        verify(statement).execute(AgeSessionSupport.LOAD_PLUGINS);
        verify(statement, never()).execute(AgeSessionSupport.LOAD_LEGACY);
    }

    @Test
    void pluginsPathRefusedFallsBackToLegacyLoad() throws SQLException {
        when(statement.execute(AgeSessionSupport.LOAD_PLUGINS)).thenThrow(new SQLException("could not access file"));

        AgeSessionSupport.loadIfNeeded(connection);

        InOrder order = inOrder(statement);
        order.verify(statement).execute(AgeSessionSupport.LOAD_PLUGINS);
        order.verify(statement).execute(AgeSessionSupport.LOAD_LEGACY);
    }

    @Test
    void bothLoadsFailingPropagatesWithBothCauses() throws SQLException {
        when(statement.execute(AgeSessionSupport.LOAD_PLUGINS)).thenThrow(new SQLException("plugins refused"));
        when(statement.execute(AgeSessionSupport.LOAD_LEGACY))
            .thenThrow(new SQLException("access to library \"age\" is not allowed"));

        assertThatThrownBy(() -> AgeSessionSupport.loadIfNeeded(connection))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("not allowed")
            .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1));
    }

    @Test
    void unreadablePreloadSettingCountsAsNotPreloaded() throws SQLException {
        when(statement.executeQuery(AgeSessionSupport.SHOW_SHARED_PRELOAD))
            .thenThrow(new SQLException("must be superuser or have privileges of pg_read_all_settings"));

        AgeSessionSupport.loadIfNeeded(connection);

        verify(statement).execute(AgeSessionSupport.LOAD_PLUGINS);
    }

    @Test
    void preloadProbeIsCached() throws SQLException {
        stubScalar(AgeSessionSupport.SHOW_SHARED_PRELOAD, "age");
        AgeSessionSupport.loadIfNeeded(connection);
        AgeSessionSupport.loadIfNeeded(connection);

        verify(statement, org.mockito.Mockito.times(1)).executeQuery(AgeSessionSupport.SHOW_SHARED_PRELOAD);
    }

    @Test
    void failedAttemptsInsideATransactionRollBackToASavepoint() throws SQLException {
        Savepoint savepoint = mock(Savepoint.class);
        when(connection.getAutoCommit()).thenReturn(false);
        when(connection.setSavepoint()).thenReturn(savepoint);
        when(statement.execute(AgeSessionSupport.LOAD_PLUGINS)).thenThrow(new SQLException("plugins refused"));

        AgeSessionSupport.loadIfNeeded(connection);

        verify(connection).rollback(savepoint);
    }

    @Test
    void openPrependsAgCatalogAndCloseRestoresThePreviousSearchPath() throws SQLException {
        try (AgeSessionSupport.Session ignored = AgeSessionSupport.open(connection)) {
            verify(setSearchPath).setString(1, "ag_catalog, \"$user\", public");
        }
        verify(setSearchPath).setString(1, "\"$user\", public");
    }

    @Test
    void openLeavesASearchPathThatAlreadyHasAgCatalogAlone() throws SQLException {
        stubScalar(AgeSessionSupport.CURRENT_SEARCH_PATH, "ag_catalog, \"$user\", public");

        try (AgeSessionSupport.Session ignored = AgeSessionSupport.open(connection)) {
            // nothing
        }

        verify(connection, never()).prepareStatement(any());
    }

    @Test
    void listsAgeParsesRealWorldValues() {
        assertThat(AgeSessionSupport.listsAge("age")).isTrue();
        assertThat(AgeSessionSupport.listsAge("pg_stat_statements, 'age'")).isTrue();
        assertThat(AgeSessionSupport.listsAge("$libdir/plugins/age.so")).isTrue();
        assertThat(AgeSessionSupport.listsAge("pg_stat_statements,pageinspect")).isFalse();
        assertThat(AgeSessionSupport.listsAge("")).isFalse();
        assertThat(AgeSessionSupport.listsAge(null)).isFalse();
    }
}
