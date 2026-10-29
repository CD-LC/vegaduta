package com.agenticai.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The second line of defence for view-as sessions: {@link
 * ImpersonationContext#assertNotImpersonating} lets a side-effecting service reject work even if a
 * mutating GET slipped past {@code ViewAsFilter}'s method check.
 */
class ImpersonationContextTest {

    private static ImpersonationContext.Session session() {
        return new ImpersonationContext.Session(
                UUID.randomUUID(),
                "admin-kc-1",
                "ops@vegaduta.ai",
                "tenant_target",
                "target-kc-9");
    }

    @AfterEach
    void clear() {
        ImpersonationContext.clear();
    }

    @Test
    void nothingIsActiveBeforeSet() {
        assertThat(ImpersonationContext.isActive()).isFalse();
        assertThat(ImpersonationContext.current()).isEmpty();
        // A no-op outside a view-as request - must not throw.
        ImpersonationContext.assertNotImpersonating("llm-call");
    }

    @Test
    void setExposesTheSessionUntilClear() {
        ImpersonationContext.Session session = session();

        ImpersonationContext.set(session);

        assertThat(ImpersonationContext.isActive()).isTrue();
        assertThat(ImpersonationContext.current()).containsSame(session);
        assertThat(session.adminEmail()).isEqualTo("ops@vegaduta.ai");
        assertThat(session.tenantSchema()).isEqualTo("tenant_target");
        assertThat(session.targetSubject()).isEqualTo("target-kc-9");

        ImpersonationContext.clear();

        assertThat(ImpersonationContext.isActive()).isFalse();
    }

    @Test
    void assertNotImpersonatingThrowsWithTheSessionIdAndActionInsideAViewAsRequest() {
        ImpersonationContext.Session session = session();
        ImpersonationContext.set(session);

        assertThatThrownBy(() -> ImpersonationContext.assertNotImpersonating("send-email"))
                .isInstanceOfSatisfying(
                        ImpersonationReadOnlyException.class,
                        e -> {
                            assertThat(e.getAction()).isEqualTo("send-email");
                            assertThat(e.getMessage())
                                    .contains(session.sessionId().toString())
                                    .contains("send-email");
                            assertThat(ImpersonationReadOnlyException.ERROR_CODE)
                                    .isEqualTo("impersonation_read_only");
                        });
    }
}
