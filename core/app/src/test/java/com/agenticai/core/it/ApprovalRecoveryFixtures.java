package com.agenticai.core.it;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Small JDBC/wait helpers of {@link ApprovalRecoveryIT}, extracted so the IT stays under the
 * file-size cap. Package-private.
 */
final class ApprovalRecoveryFixtures {

    private ApprovalRecoveryFixtures() {
        // utility class
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A fresh, detached copy as the database holds it now. */
    static Instant completedAt(UUID id) {
        Timestamp t =
                ApprovalRecoveryIT.jdbc.queryForObject(
                        "SELECT execution_completed_at FROM approval_request WHERE id = ?",
                        Timestamp.class,
                        id);
        return t == null ? null : t.toInstant();
    }

    static boolean columnExists(String column) {
        Integer n =
                ApprovalRecoveryIT.jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.columns WHERE table_schema = ? AND table_name = 'approval_request'"
                                + " AND column_name = ?",
                        Integer.class,
                        ApprovalRecoveryIT.SCHEMA,
                        column);
        return n != null && n == 1;
    }

    static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
