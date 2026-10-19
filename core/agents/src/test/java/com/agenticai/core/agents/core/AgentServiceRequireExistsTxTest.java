package com.agenticai.core.agents.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pins the transaction contract of {@link AgentService#requireExists}.
 * OrchestratorService#validate (read-only tx) catches AgentNotFoundException from it and
 * reports a validation error. If the exception marked the shared transaction rollback-only,
 * the caller's commit would throw UnexpectedRollbackException (HTTP 500 on
 * POST /api/workflows/{id}/validate for a workflow whose agent node was deleted).
 */
class AgentServiceRequireExistsTxTest {

    @Test
    void requireExistsDoesNotMarkTheSharedTransactionRollbackOnlyForAMissingAgent() throws Exception {
        Transactional tx = AgentService.class.getMethod("requireExists", UUID.class).getAnnotation(Transactional.class);
        assertNotNull(tx);
        assertTrue(tx.readOnly());
        assertArrayEquals(new Class<?>[] {AgentNotFoundException.class}, tx.noRollbackFor());
    }
}
