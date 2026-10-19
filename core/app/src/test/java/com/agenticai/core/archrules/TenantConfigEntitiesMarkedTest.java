package com.agenticai.core.archrules;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.security.PlatformConfigEntity;
import com.agenticai.core.security.TenantConfigEntity;
import jakarta.persistence.Entity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every entity that a {@code HotpathLookups.tenant(...)} lookup reads must be marked {@link
 * TenantConfigEntity}, or a write to it would leave a stale cached answer for up to the TTL on the
 * pod that made the write. The list is the lookups as written today; add to it with every new
 * lookup.
 */
class TenantConfigEntitiesMarkedTest {

    private static final List<String> READ_BY_A_LOOKUP =
            List.of(
                    "com.agenticai.core.mcp.McpServerRegistration",
                    "com.agenticai.core.sdlc.E2bCredential",
                    "com.agenticai.core.elevenlabs.ElevenLabsCredential",
                    "com.agenticai.core.sarvam.SarvamCredential",
                    "com.agenticai.core.providers.LlmProviderCredential",
                    "com.agenticai.core.payments.PaymentMandate",
                    "com.agenticai.core.agents.crew.AgentSpecialization",
                    "com.agenticai.core.google.GoogleCredential",
                    "com.agenticai.core.agents.skills.Skill",
                    "com.agenticai.core.agents.skills.SkillBuiltInOverride",
                    "com.agenticai.core.agents.skills.AgentSkillConfig",
                    "com.agenticai.core.agents.tools.script.UserToolScript",
                    "com.agenticai.core.agents.tools.code.CustomCodeTool",
                    "com.agenticai.core.agents.tools.openapi.OpenApiTool",
                    "com.agenticai.core.agents.tools.openapi.OpenApiToolSource");

    /** Entities read by a {@code HotpathLookups.platform(...)} lookup (same for every tenant). */
    private static final List<String> READ_BY_A_PLATFORM_LOOKUP =
            List.of(
                    "com.agenticai.core.providers.PlatformHostedLlmProvider",
                    "com.agenticai.core.providers.HostedLlmPoolSettings");

    @Test
    void everyEntityReadByATenantLookupIsMarked() throws Exception {
        List<String> unmarked = new ArrayList<>();
        for (String name : READ_BY_A_LOOKUP) {
            Class<?> type = Class.forName(name);
            assertThat(type.isAnnotationPresent(Entity.class))
                    .as(name + " is a JPA entity")
                    .isTrue();
            if (!type.isAnnotationPresent(TenantConfigEntity.class)) {
                unmarked.add(name);
            }
        }
        assertThat(unmarked)
                .as("a lookup reads these but a write would not evict its cached answer")
                .isEmpty();
    }

    @Test
    void everyEntityReadByAPlatformLookupIsMarked() throws Exception {
        List<String> unmarked = new ArrayList<>();
        for (String name : READ_BY_A_PLATFORM_LOOKUP) {
            Class<?> type = Class.forName(name);
            assertThat(type.isAnnotationPresent(Entity.class))
                    .as(name + " is a JPA entity")
                    .isTrue();
            if (!type.isAnnotationPresent(PlatformConfigEntity.class)) {
                unmarked.add(name);
            }
        }
        assertThat(unmarked)
                .as("a platform lookup reads these but a write would not evict its cached answer")
                .isEmpty();
    }
}
