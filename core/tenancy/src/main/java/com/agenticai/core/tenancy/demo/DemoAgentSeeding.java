package com.agenticai.core.tenancy.demo;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort seeding and cleanup of a sandbox's agents in the shared demo schema, always under
 * the sandbox's own {@code created_by} subject (see {@link DemoTenantContextScope}) so a seeded
 * agent is invisible to every other session sharing the schema. Created and owned by {@link
 * DemoSessionService}; not a bean.
 */
final class DemoAgentSeeding {

    private static final Logger log = LoggerFactory.getLogger(DemoAgentSeeding.class);

    private final Optional<DemoWorkspaceSeeder> seeder;
    private final DemoSessionRecorder recorder;

    DemoAgentSeeding(Optional<DemoWorkspaceSeeder> seeder, DemoSessionRecorder recorder) {
        this.seeder = seeder;
        this.recorder = recorder;
    }

    DemoWorkspaceSeeder.SeededAgent seedAgentQuietly(
        String schemaName, String businessName, DemoPersona persona, UUID sessionId
    ) {
        if (seeder.isEmpty()) {
            log.warn("No DemoWorkspaceSeeder on the classpath - demo schema {} provisioned without an agent", schemaName);
            return null;
        }
        try {
            DemoWorkspaceSeeder.SeededAgent seeded = DemoTenantContextScope.call(schemaName, sessionId, () ->
                seeder.get().seedDemoAgent(schemaName, businessName, persona)
            );
            if (seeded != null) {
                recorder.recordSeededAgent(sessionId, seeded.agentId());
            }
            return seeded;
        } catch (RuntimeException e) {
            log.warn("Demo agent seeding failed for schema {} - sandbox stays usable, chat will retry the seed", schemaName, e);
            return null;
        }
    }

    /**
     * Best-effort counterpart of {@link #seedAgentQuietly} - see {@link
     * DemoSessionService#deleteSession} and {@link DemoSessionService#retire}.
     */
    void deleteSeededAgentQuietly(DemoSession session) {
        if (seeder.isEmpty()) {
            return;
        }
        try {
            DemoTenantContextScope.call(session.getTenantSchema(), session.getId(), () -> {
                if (session.getAgentId() != null) {
                    seeder.get().deleteDemoAgent(session.getTenantSchema(), session.getAgentId());
                }
                // Studio-built and Magic Studio agents carry the same
                // created_by subject - sweep them too (V339 follow-up), or
                // they outlive the sandbox in the shared schema.
                seeder.get().deleteAgentsCreatedBy(
                    session.getTenantSchema(), DemoSessionService.actorSubject(session.getId()));
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("Best-effort seeded-agent cleanup failed for demo session {}", session.getId(), e);
        }
    }
}
