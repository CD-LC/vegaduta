package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.a2a.A2aProperties;
import com.agenticai.core.agents.a2a.A2aTask;
import com.agenticai.core.agents.a2a.A2aTaskService;
import com.agenticai.core.agents.a2a.A2aTaskStore;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantMigrationService;
import com.agenticai.core.tenancy.TenantRepository;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Work that ends on a Reactor/HTTP thread with no {@code TenantContext} must still land in the
 * right tenant's schema, on a real Postgres. The A2A task's terminal audit row is written from the
 * reply stream's completion callback; two tenants, so a write into the other schema is visible as
 * well as a lost one. (Before the fix the audit write ran on the delivering thread with no tenant,
 * failed against the fail-closed schema, was logged and dropped: every A2A task went unaudited.)
 */
@Tag("integration")
class TenantContextOnReactiveHopsIT extends AbstractContextIT {

    private static final String A = "tenant_it_reactive_hop_a";
    private static final String B = "tenant_it_reactive_hop_b";

    @Autowired private TenantRepository tenants;
    @Autowired private TenantMigrationService migrations;
    @Autowired private AuditLogService auditLog;
    @Autowired private A2aTaskStore store;
    @Autowired private JdbcTemplate jdbc;

    private Scheduler delivery;

    @BeforeEach
    void tenantSchemas() {
        boolean created = false;
        for (String schema : List.of(A, B)) {
            if (tenants.findBySchemaName(schema).isEmpty()) {
                Tenant tenant =
                        new Tenant(
                                schema,
                                TenantPlan.PREMIUM,
                                schema,
                                "agentic-ai",
                                AccountType.B2B,
                                ExperienceMode.SIMPLE);
                tenant.markActive();
                tenants.save(tenant);
                created = true;
            }
        }
        if (created) {
            assertThat(migrations.migrateAllActiveTenants()).as("schemas that failed").isEmpty();
        }
        delivery = Schedulers.newSingle("a2a-it-delivery");
        TenantContext.clear();
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
        delivery.dispose();
    }

    private long auditRows(String schema, UUID agentId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM "
                        + schema
                        + ".audit_log_entry WHERE action_type = 'a2a.task.send' AND target_id = ?",
                Long.class,
                agentId.toString());
    }

    private void runATaskAs(String tenantId, UUID agentId, Flux<String> reply) throws Exception {
        AgentChatService chat = mock(AgentChatService.class);
        when(chat.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), agentId)
                                                && r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null)))
                .thenReturn(reply.publishOn(delivery));
        A2aTaskService service = new A2aTaskService(chat, store, auditLog, new A2aProperties());
        Principal alice = () -> "alice";
        TenantContext.set(TenantContext.of(tenantId, "alice", Set.of()));
        try {
            A2aTask task = service.create(agentId, "ctx-" + UUID.randomUUID(), "m", "hello", alice);
            service.run(task, null).get(10, TimeUnit.SECONDS);
        } finally {
            TenantContext.clear();
        }
        long deadline = System.currentTimeMillis() + 10_000;
        while (auditRows(A, agentId) + auditRows(B, agentId) == 0
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
    }

    @Test
    void aCompletedA2aTaskIsAuditedInItsOwnTenantOnly() throws Exception {
        UUID agentId = UUID.randomUUID();

        runATaskAs(A, agentId, Flux.just("hello", " there"));

        assertThat(auditRows(A, agentId)).as("audit rows in A").isEqualTo(1);
        assertThat(auditRows(B, agentId)).as("audit rows in B").isZero();
    }

    @Test
    void aFailedA2aTaskIsAuditedInItsOwnTenantOnly() throws Exception {
        UUID agentId = UUID.randomUUID();

        runATaskAs(B, agentId, Flux.<String>error(new IllegalStateException("provider down")));

        assertThat(auditRows(B, agentId)).as("audit rows in B").isEqualTo(1);
        assertThat(auditRows(A, agentId)).as("audit rows in A").isZero();
    }
}
