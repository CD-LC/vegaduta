package com.agenticai.core.common.systemstatus;

import com.agenticai.core.common.systemstatus.SystemStatusResponse.DatabaseStatus;
import com.agenticai.core.eventbus.WorkflowEventOutboxStatsService;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

/**
 * Data-source and outbox reads behind {@link SystemStatusController}. Holds the documented {@code
 * dataSource.getConnection()} site (a pooled connection borrowed only for {@code DatabaseMetaData},
 * released immediately) and the outbox counts, so the controller stays free of JDBC and repository
 * access.
 *
 * <p>The outbox table exists in every tenant schema unconditionally (Flyway tenant migrations), so
 * these counts just return zero when the workflow-events feature is unused.
 */
@Service
public class SystemStatusDataService {

    private final DataSource dataSource;
    private final WorkflowEventOutboxStatsService outboxStats;

    public SystemStatusDataService(
            DataSource dataSource, WorkflowEventOutboxStatsService outboxStats) {
        this.dataSource = dataSource;
        this.outboxStats = outboxStats;
    }

    public DatabaseStatus databaseStatus(String status) {
        String product = null;
        try (Connection connection = dataSource.getConnection()) {
            var meta = connection.getMetaData();
            product = meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
        } catch (SQLException e) {
            // Leave product null - the status code above already reflects
            // a DB problem in this case; no need to fabricate a name.
        }

        Integer active = null;
        Integer idle = null;
        Integer total = null;
        Integer maxPool = null;
        Integer awaiting = null;
        if (dataSource instanceof HikariDataSource hikari) {
            maxPool = hikari.getMaximumPoolSize();
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            // Null until the pool has lazily initialized (e.g. immediately
            // after startup, before the first real query) - left null
            // rather than reported as zero, which would misleadingly read
            // as "confirmed empty" instead of "not yet known".
            if (pool != null) {
                active = pool.getActiveConnections();
                idle = pool.getIdleConnections();
                total = pool.getTotalConnections();
                awaiting = pool.getThreadsAwaitingConnection();
            }
        }

        return new DatabaseStatus(status, product, active, idle, total, maxPool, awaiting);
    }

    public long outboxPendingCount() {
        return outboxStats.pendingCount();
    }

    public long outboxDeadLetteredCount() {
        return outboxStats.deadLetteredCount();
    }
}
