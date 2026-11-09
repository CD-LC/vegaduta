package com.agenticai.core.settings;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenant's schema is selected on a transaction's connection. The two reads that fill the effective-value cache are
 * called from threads that are often not in a transaction (the effective-settings endpoint, a chat turn), so they must
 * open their own read-only one: without it the query ran against the default schema, found no rows, and every tenant
 * and agent override was invisible (the write answered with the override, the next read with the platform value).
 * A unit test cannot run the real schema switch (see SettingsStoreIT, which needs Postgres); this pins the annotation.
 */
class JdbcSettingsStoreTransactionTest {

    @Test
    void tenantValuesRunsInItsOwnReadOnlyTransaction() throws Exception {
        assertReadOnlyTransactional(JdbcSettingsStore.class.getMethod("tenantValues"));
    }

    @Test
    void agentValuesRunsInItsOwnReadOnlyTransaction() throws Exception {
        assertReadOnlyTransactional(JdbcSettingsStore.class.getMethod("agentValues", String.class));
    }

    private static void assertReadOnlyTransactional(Method m) {
        Transactional tx = m.getAnnotation(Transactional.class);
        assertNotNull(tx, m.getName() + " must be @Transactional so the tenant schema is selected");
        assertTrue(tx.readOnly(), m.getName() + " only reads");
    }
}
