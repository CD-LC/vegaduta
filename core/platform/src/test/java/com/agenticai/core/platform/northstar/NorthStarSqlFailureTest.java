package com.agenticai.core.platform.northstar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.persistence.QueryTimeoutException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import org.junit.jupiter.api.Test;

class NorthStarSqlFailureTest {

    @Test
    void undefinedTableAndColumnAreMissingSource() {
        assertEquals(NorthStarSqlFailure.MISSING_SOURCE,
            NorthStarSqlFailure.classify(new RuntimeException("wrap", new SQLException("no table", "42P01"))));
        assertEquals(NorthStarSqlFailure.MISSING_SOURCE,
            NorthStarSqlFailure.classify(new RuntimeException(new SQLException("no column", "42703"))));
    }

    @Test
    void queryCancelledAndTimeoutTypesAreTimeouts() {
        assertEquals(NorthStarSqlFailure.TIMEOUT,
            NorthStarSqlFailure.classify(new RuntimeException(new SQLException("canceled", "57014"))));
        assertEquals(NorthStarSqlFailure.TIMEOUT, NorthStarSqlFailure.classify(new SQLTimeoutException("t")));
        assertEquals(NorthStarSqlFailure.TIMEOUT,
            NorthStarSqlFailure.classify(new IllegalStateException(new QueryTimeoutException("t"))));
    }

    @Test
    void anythingElseIsAnError() {
        assertEquals(NorthStarSqlFailure.ERROR, NorthStarSqlFailure.classify(new RuntimeException("boom")));
        assertEquals(NorthStarSqlFailure.ERROR,
            NorthStarSqlFailure.classify(new RuntimeException(new SQLException("conn", "08006"))));
        assertEquals(NorthStarSqlFailure.ERROR, NorthStarSqlFailure.classify(null));
    }

    @Test
    void aTimeoutWinsOverAnEarlierMissingSourceInTheChain() {
        SQLException timeout = new SQLException("canceled", "57014");
        SQLException missing = new SQLException("no table", "42P01", timeout);
        assertEquals(NorthStarSqlFailure.TIMEOUT, NorthStarSqlFailure.classify(new RuntimeException(missing)));
    }
}
