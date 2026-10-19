package com.agenticai.core.agents.a2a;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.audit.AuditLogService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Outbound-call bookkeeping records share the task store but must be invisible to inbound GetTask/CancelTask. */
class A2aOutboundNamespaceTest {

    @Test
    void inboundGetAndCancelRefuseOutboundIds() {
        A2aTaskStore store = new A2aTaskStore(Duration.ofHours(1), 100);
        A2aTask outbound = new A2aTask(A2aTask.OUTBOUND_ID_PREFIX + UUID.randomUUID(), "ctx", UUID.randomUUID(),
            null, null, "m1", "secret prompt");
        store.put(outbound);
        // Same tenant/subject (both null) would otherwise match - the namespace is what blocks it.
        assertTrue(store.find(outbound.id(), null, null).isPresent());

        A2aTaskService service = new A2aTaskService(
            mock(AgentChatService.class), store, mock(AuditLogService.class), new A2aProperties());
        assertThrows(A2aError.class, () -> service.get(outbound.id(), null));
        assertThrows(A2aError.class, () -> service.cancel(outbound.id(), null));
    }
}
