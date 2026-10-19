package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.security.AgentVersionPin;
import com.agenticai.core.security.RequestCorrelationFilter;
import com.agenticai.core.security.TenantContext;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** ADR-0022 stage 4: the context an entry builds, and how it is applied on the entry thread. */
class TurnContextEntryTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final ChatTurnRequest REQUEST =
            ChatTurnRequest.builder(AGENT, "s1", "subject", "hello").build();

    @AfterEach
    void clear() {
        TenantContext.clear();
        AgentVersionPin.clear();
        MDC.remove(RequestCorrelationFilter.MDC_KEY);
    }

    @Test
    void forEntryCarriesTheGivenTenantAndTheRequestsIds() {
        TenantContext tenant = TenantContext.of("tenant_a", "user-1", Set.of());
        MDC.put(RequestCorrelationFilter.MDC_KEY, "req-42");

        TurnContext ctx = TurnContext.forEntry(tenant, REQUEST);

        assertThat(ctx.tenant()).isSameAs(tenant);
        assertThat(ctx.requestedAgentId()).isEqualTo(AGENT);
        assertThat(ctx.sessionId()).isEqualTo("s1");
        assertThat(ctx.requestId()).isEqualTo("req-42");
        assertThat(ctx.deadline()).isNull();
        assertThat(ctx.budget()).isNull();
    }

    @Test
    void forEntryWithoutAnMdcRequestIdGeneratesOne() {
        assertThat(TurnContext.forEntry(null, REQUEST).requestId()).isNotBlank();
    }

    @Test
    void atEntryIsForEntryWithTheCallingThreadsTenant() {
        TenantContext tenant = TenantContext.of("tenant_a", "user-1", Set.of());
        TenantContext.set(tenant);

        assertThat(TurnContext.atEntry(REQUEST).tenant()).isSameAs(tenant);
    }

    @Test
    void onEntryThreadLeavesAThreadThatAlreadyCarriesTheTenantUntouched() {
        TenantContext tenant = TenantContext.of("tenant_a", "user-1", Set.of());
        TenantContext.set(tenant);
        UUID pin = UUID.randomUUID();
        TurnContext ctx = TurnContext.forEntry(tenant, REQUEST);

        // The preflight pins the agent version on the request thread; the pin must still be
        // there for the subscription afterwards, exactly as before stage 4.
        String seen =
                ctx.onEntryThread(
                        () -> {
                            AgentVersionPin.set(pin);
                            return TenantContext.current().orElseThrow().getTenantId();
                        });

        assertThat(seen).isEqualTo("tenant_a");
        assertThat(TenantContext.current()).containsSame(tenant);
        assertThat(AgentVersionPin.current()).isEqualTo(pin);
    }

    @Test
    void onEntryThreadAppliesADifferentTenantAndRestoresOnlyTheTenant() {
        TenantContext outer = TenantContext.of("tenant_outer", "svc", Set.of());
        TenantContext.set(outer);
        TenantContext turnTenant = TenantContext.of("tenant_turn", "user-1", Set.of());
        TurnContext ctx = TurnContext.forEntry(turnTenant, REQUEST);
        UUID pin = UUID.randomUUID();

        TenantContext seen =
                ctx.onEntryThread(
                        () -> {
                            AgentVersionPin.set(pin);
                            return TenantContext.current().orElseThrow();
                        });

        assertThat(seen).isSameAs(turnTenant);
        assertThat(TenantContext.current()).containsSame(outer);
        assertThat(AgentVersionPin.current()).isEqualTo(pin);
    }

    @Test
    void onEntryThreadClearsWhatItAppliedOnAThreadWithoutATenantEvenOnFailure() {
        TurnContext ctx =
                TurnContext.forEntry(TenantContext.of("tenant_turn", "u", Set.of()), REQUEST);

        assertThatThrownBy(
                        () ->
                                ctx.onEntryThread(
                                        () -> {
                                            throw new IllegalStateException("kill switch");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void aTenantLessContextRunsTheWorkUntouched() {
        TenantContext outer = TenantContext.of("tenant_outer", "svc", Set.of());
        TenantContext.set(outer);

        TenantContext seen =
                TurnContext.forEntry(null, REQUEST)
                        .onEntryThread(() -> TenantContext.current().orElse(null));

        assertThat(seen).isSameAs(outer);
        assertThat(TenantContext.current()).containsSame(outer);
    }
}
