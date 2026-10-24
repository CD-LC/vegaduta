package com.agenticai.core.agents.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.agenticai.core.agents.auxiliary.AuxiliaryModelProperties;
import com.agenticai.core.agents.auxiliary.AuxiliaryModelResolver;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.turn.AgentServiceAuxiliaryAgentModels;
import com.agenticai.core.tenancy.TenantAiModelPreferenceService;
import org.springframework.beans.factory.ObjectProvider;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the SYNCHRONOUS half of {@link SessionSummaryService} — the guards
 * that decide whether a fold happens at all, before anything is handed to a
 * background thread.
 *
 * <p>Only that half is tested here, deliberately. Everything past the guards
 * runs on {@code FOLD_EXECUTOR} and returns nothing to await, which is the
 * whole point of the design ({@code appendTurn} must never block on an LLM
 * call), so asserting on it would mean either exposing the future purely for
 * tests or polling with a timeout — both worse than leaving the LLM call to
 * the integration surface. What matters here, and what a refactor could
 * plausibly break, is the set of conditions under which this service refuses
 * to do anything: those are what stand between an optional enhancement and the
 * chat write path.
 *
 * <p>Plain JUnit 5 + Mockito, no Spring context and no live provider — same
 * posture as {@code SessionMemoryServiceTest}.
 */
class SessionSummaryServiceTest {

    private ProviderCredentialResolver credentialResolver;
    private LlmGatewayService llmGatewayService;
    private ProviderModelCatalogService providerModelCatalogService;
    private TenantLimits tenantLimits;
    private FreeTierChatClientService freeTierChatClientService;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        credentialResolver = mock(ProviderCredentialResolver.class);
        llmGatewayService = mock(LlmGatewayService.class);
        providerModelCatalogService = mock(ProviderModelCatalogService.class);
        tenantLimits = mock(TenantLimits.class);
        freeTierChatClientService = mock(FreeTierChatClientService.class);
    }

    private SessionSummaryService newService(boolean enabled) {
        @SuppressWarnings("unchecked")
        ObjectProvider<TenantAiModelPreferenceService> noPreferences = mock(ObjectProvider.class);
        AuxiliaryModelResolver auxiliary = new AuxiliaryModelResolver(
            new AuxiliaryModelProperties(), credentialResolver, providerModelCatalogService, new AgentServiceAuxiliaryAgentModels(mock(AgentService.class)),
            noPreferences, "claude-sonnet-4-20250514");
        return new SessionSummaryService(
            auxiliary, llmGatewayService, tenantLimits, freeTierChatClientService, enabled, 600, 4000, 800, 24000
        );
    }

    private List<ChatTurn> oneTurn() {
        return List.of(new ChatTurn(MessageRole.USER, "how do I rotate the signing key?", Instant.parse("2026-08-17T00:00:00Z")));
    }

    private Optional<TenantContext> tenant() {
        return Optional.of(TenantContext.of("tenant-1", "user-1", Set.of("USER")));
    }

    /** Captures whether the persist callback ever fired, without needing a mock of a functional interface. */
    private static final class RecordingPersist implements Consumer<String> {
        private volatile boolean called;

        @Override
        public void accept(String summary) {
            called = true;
        }
    }

    @Test
    void disabledServiceReportsItselfDisabledAndFoldsNothing() {
        SessionSummaryService service = newService(false);
        RecordingPersist persist = new RecordingPersist();

        // isEnabled() is what SessionMemoryService checks BEFORE doing any of
        // the extra Redis work a fold needs, so the switch has to be visible
        // there, not just honored in here.
        assertFalse(service.isEnabled());

        service.foldAsync("sess-1", tenant(), null, oneTurn(), persist);

        assertFalse(persist.called);
        verifyNoProviderResolutionAttempted();
    }

    @Test
    void missingTenantContextSkipsTheFoldEntirely() {
        SessionSummaryService service = newService(true);
        RecordingPersist persist = new RecordingPersist();

        // appendTurn legitimately reaches this service from the stream-
        // completion thread, where the ambient TenantContext is already gone.
        // Running anyway would resolve credentials/plan against no tenant -
        // and TenantSchemaResolver silently falls back to the `public` schema
        // when the context is empty, which is how every document-generation
        // job on the platform sat in REQUESTED for three days in 2026-08-10.
        // Skipping costs one fold; not skipping costs correctness.
        service.foldAsync("sess-1", Optional.empty(), null, oneTurn(), persist);

        assertFalse(persist.called);
        verifyNoProviderResolutionAttempted();
    }

    @Test
    void nullTenantSnapshotIsTreatedAsMissingRatherThanDereferenced() {
        SessionSummaryService service = newService(true);
        RecordingPersist persist = new RecordingPersist();

        // Defensive: the argument is an Optional, but this method's contract is
        // "never throws" and a null here must land on the same skip as empty.
        service.foldAsync("sess-1", null, null, oneTurn(), persist);

        assertFalse(persist.called);
        verifyNoProviderResolutionAttempted();
    }

    @Test
    void emptyEvictionListIsANoOp() {
        SessionSummaryService service = newService(true);
        RecordingPersist persist = new RecordingPersist();

        service.foldAsync("sess-1", tenant(), "existing summary", List.of(), persist);
        service.foldAsync("sess-1", tenant(), "existing summary", null, persist);

        assertFalse(persist.called);
        verifyNoProviderResolutionAttempted();
    }

    @Test
    void nullPersistCallbackIsANoOpRatherThanAnNpe() {
        SessionSummaryService service = newService(true);

        // Nothing about a caller bug here may reach appendTurn as an exception:
        // this method is invoked mid-way through a Redis write on the chat
        // path, so it has to absorb bad input, not report it.
        service.foldAsync("sess-1", tenant(), null, oneTurn(), null);

        verifyNoProviderResolutionAttempted();
    }

    @Test
    void guardsRunOnTheCallingThreadAndLeaveNoTenantContextBehind() {
        SessionSummaryService service = newService(true);

        service.foldAsync("sess-1", tenant(), null, oneTurn(), new RecordingPersist());

        // The tenant travels as data and is applied on the BACKGROUND thread.
        // If a guard path ever started setting it here instead, this thread
        // would leak a tenant into whatever ran next on it - the calling thread
        // is a request thread that has its own context management.
        assertTrue(TenantContext.current().isEmpty());
    }

    /**
     * No fold means no provider work at all — not a resolved-then-discarded
     * client, and in particular not a {@code buildClientForFreeTenant} call,
     * which consumes a message unit against the tenant's usage cap.
     */
    private void verifyNoProviderResolutionAttempted() {
        verify(credentialResolver, never()).resolveBest(org.mockito.ArgumentMatchers.anyBoolean());
        verify(tenantLimits, never()).plan(anyString());
        // Mockito's own view of the mock, so this stays true even if the
        // free-tier builder grows another overload.
        assertTrue(mockingDetails(freeTierChatClientService).getInvocations().isEmpty());
    }
}
