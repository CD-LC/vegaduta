package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The replay-safety word lists and the low-confidence markers are registry data now. The shipped
 * lists behave exactly as the old constants did, a tenant or agent can add to the mutating words and
 * never-replay tools but not remove from them, and an override reaches the real decision path.
 */
class VerifierEscalationRouterListsTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());
    private final VerifierEscalationRouter router =
            new VerifierEscalationRouter(new SimpleMeterRegistry(), true, false, 2, 0.2);

    VerifierEscalationRouterListsTest() {
        router.setPromptResolver(resolver);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void override(PromptScope scope, String key, String text) {
        TenantContext.set(TenantContext.of("a", "owner", Set.of()));
        store.put(
                new PromptOverride(
                        scope, scope == PromptScope.AGENT ? AGENT.toString() : "", key, "en", text, true, 1, "owner", Instant.now()));
        resolver.evictAll();
    }

    @Test
    void shippedListsDecideLikeTheOldConstants() {
        assertThat(router.replaySafe(List.of("search_flights", "get_calendar"))).isTrue();
        assertThat(router.replaySafe(List.of("browse_page"))).isTrue();
        assertThat(router.replaySafe(List.of("browser_click"))).isFalse();
        assertThat(router.replaySafe(List.of("get_and_delete"))).isFalse();
        assertThat(router.replaySafe(List.of("http_request"))).isFalse();
        assertThat(router.replaySafe(List.of("http_request_write"))).isFalse();
        assertThat(router.replaySafe(List.of("unknown_tool"))).isFalse();
        assertThat(VerifierEscalationRouter.replaySafe(List.of("list_events"), (java.util.function.Function<String, java.util.Optional<Boolean>>) null)).isTrue();
        assertThat(VerifierEscalationRouter.replaySafe(List.of("send_email"), (java.util.function.Function<String, java.util.Optional<Boolean>>) null)).isFalse();
    }

    @Test
    void aTenantCanAddAMutatingWordButNeverRemoveOne() {
        override(PromptScope.TENANT, "detector.replay.mutating_words", "ship\n# a comment\n");

        assertThat(router.replaySafe(List.of("list_and_ship"))).isFalse();
        // "delete" is in the shipped list and the override did not repeat it: still mutating.
        assertThat(router.replaySafe(List.of("get_and_delete"))).isFalse();
        assertThat(router.replaySafe(List.of("list_events"))).isTrue();
    }

    @Test
    void aTenantCanAddANeverReplayToolButNeverRemoveOne() {
        override(PromptScope.TENANT, "detector.replay.never_replay_tools", "get_invoice_pdf");

        assertThat(router.replaySafe(List.of("get_invoice_pdf"))).isFalse();
        assertThat(router.replaySafe(List.of("http_request"))).isFalse();
    }

    @Test
    void anAgentOverrideOfThePrefixesAppliesToThatAgentOnly() {
        override(PromptScope.AGENT, "detector.replay.read_only_prefixes", "inspect\nget");

        assertThat(router.replaySafe(List.of("inspect_order"), AGENT)).isTrue();
        assertThat(router.replaySafe(List.of("inspect_order"), UUID.randomUUID())).isFalse();
        // Dropping a prefix only makes the check stricter.
        assertThat(router.replaySafe(List.of("list_events"), AGENT)).isFalse();
        assertThat(router.replaySafe(List.of("list_events"), UUID.randomUUID())).isTrue();
    }

    @Test
    void lowConfidenceMarkersComeFromTheRegistryAndAnOverrideApplies() {
        assertThat(router.inspect("I don't know that.", null, 0, 0).lowConfidence()).isTrue();
        assertThat(router.inspect("Das weiss ich nicht.", null, 0, 0).lowConfidence()).isFalse();

        override(PromptScope.TENANT, "detector.escalation.low_confidence_markers", "weiss ich nicht\nich bin nicht sicher");

        assertThat(router.inspect("Das weiss ich nicht.", null, 0, 0).lowConfidence()).isTrue();
        assertThat(router.inspect("I don't know that.", null, 0, 0).lowConfidence()).isFalse();
    }

    @Test
    void aMarkerListShippedForAnotherLocaleIsAppliedInAddition() {
        String front = "---\ntitle: Markers\ndescription: test\nrisk: SECONDARY_CALL\nmaxLength: 1000\n---\n";
        com.agenticai.core.prompts.PromptCatalog catalog = com.agenticai.core.prompts.PromptCatalog.fromSources(
                java.util.Map.of(
                        "prompts/en/detector.escalation.low_confidence_markers.md", front + "i don't know\n",
                        "prompts/de/detector.escalation.low_confidence_markers.md", "weiss ich nicht\nkeine ahnung\n"),
                "en",
                8000);
        VerifierEscalationRouter localized = new VerifierEscalationRouter(new SimpleMeterRegistry(), true, false, 2, 0.2);
        localized.setPromptResolver(new DefaultPromptResolver(catalog, store, new PromptProperties()));

        assertThat(localized.inspect("I don't know that.", null, 0, 0).lowConfidence()).isTrue();
        assertThat(localized.inspect("Keine Ahnung, ehrlich.", null, 0, 0).lowConfidence()).isTrue();
        assertThat(localized.inspect("Das ist klar.", null, 0, 0).lowConfidence()).isFalse();
    }

    @Test
    void theDeprecatedPropertyIsOnlyThePlatformDefaultSoAnOverrideBeatsIt() {
        VerifierEscalationRouter withProperty = new VerifierEscalationRouter(new SimpleMeterRegistry(), true, false, 2, 0.2);
        DefaultPromptResolver aliased =
                new DefaultPromptResolver(
                        PromptResolver.defaultsOnly().catalog(),
                        store,
                        new PromptProperties(),
                        java.util.List.of(PromptPropertyAliases.lowConfidenceMarkers("no idea")));
        withProperty.setPromptResolver(aliased);

        assertThat(withProperty.inspect("No idea at all.", null, 0, 0).lowConfidence()).isTrue();
        assertThat(withProperty.inspect("I don't know that.", null, 0, 0).lowConfidence()).isFalse();

        override(PromptScope.TENANT, "detector.escalation.low_confidence_markers", "weiss ich nicht");
        aliased.evictAll();

        assertThat(withProperty.inspect("Das weiss ich nicht.", null, 0, 0).lowConfidence()).isTrue();
        assertThat(withProperty.inspect("No idea at all.", null, 0, 0).lowConfidence()).isFalse();
    }
}
