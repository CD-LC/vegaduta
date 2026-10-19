package com.agenticai.core.platform.northstar;

import jakarta.persistence.QueryTimeoutException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;

/**
 * Why one per-tenant metrics query failed, so the endpoint can say "the
 * {@code quick_task_run} table does not exist in 2 tenants" instead of
 * silently reporting zeros. Pure: classifies an exception chain by SQLSTATE
 * and type, nothing else.
 *
 * <p>SQLSTATEs: 42P01 undefined_table, 42703 undefined_column, 57014
 * query_canceled (what a JDBC statement timeout raises on PostgreSQL).
 * https://www.postgresql.org/docs/current/errcodes-appendix.html
 */
public enum NorthStarSqlFailure {
    /** The table or a column the query needs does not exist in that tenant schema. */
    MISSING_SOURCE,
    /** The per-query timeout fired. */
    TIMEOUT,
    /** Anything else (connection loss, bad data, ...). */
    ERROR;

    private static final int MAX_CAUSE_DEPTH = 12;

    public static NorthStarSqlFailure classify(Throwable error) {
        NorthStarSqlFailure result = ERROR;
        Throwable t = error;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (t instanceof QueryTimeoutException || t instanceof SQLTimeoutException) {
                return TIMEOUT;
            }
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                if ("57014".equals(state)) {
                    return TIMEOUT;
                }
                if ("42P01".equals(state) || "42703".equals(state)) {
                    result = MISSING_SOURCE;
                }
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return result;
    }
}
