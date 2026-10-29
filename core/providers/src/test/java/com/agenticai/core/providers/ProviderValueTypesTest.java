package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The small value types of this package: the provider-name enum and the
 * stored-string enums/constants classes whose parsing and normalization rules
 * are the contract (an unrecognized stored value must degrade to null/a
 * default, never throw - rows written by a newer or older build still load).
 */
class ProviderValueTypesTest {

    // --- ProviderName --------------------------------------------------------

    @Test
    void providerNameCarriesEveryVendorThisPlatformKnows() {
        assertEquals(16, ProviderName.values().length);
        assertEquals(ProviderName.OPENAI, ProviderName.valueOf("OPENAI"));
        assertEquals(ProviderName.ANTHROPIC, ProviderName.valueOf("ANTHROPIC"));
        // Same-sounding names, unrelated companies - see ProviderName's javadoc.
        assertNotEquals(ProviderName.GROK, ProviderName.GROQ);
        // Not a tenant-selectable provider: the attribution marker for
        // platform-subsidized FREE-plan calls.
        assertEquals(ProviderName.PLATFORM_HOSTED, ProviderName.valueOf("PLATFORM_HOSTED"));
    }

    // --- ProviderStatus ------------------------------------------------------

    @Test
    void providerStatusIsTheThreeCredentialLifecycleStates() {
        assertEquals(3, ProviderStatus.values().length);
        assertEquals(ProviderStatus.ACTIVE, ProviderStatus.valueOf("ACTIVE"));
        assertEquals(ProviderStatus.INVALID, ProviderStatus.valueOf("INVALID"));
        assertEquals(ProviderStatus.INACTIVE, ProviderStatus.valueOf("INACTIVE"));
    }

    // --- ToolRouterMode ------------------------------------------------------

    @Test
    void toolRouterModeFromStoredParsesCaseInsensitivelyAndToleratesUnknownText() {
        assertEquals(ToolRouterMode.FORMAT, ToolRouterMode.fromStored("FORMAT"));
        assertEquals(ToolRouterMode.REQUIRED, ToolRouterMode.fromStored(" required "));
        assertNull(ToolRouterMode.fromStored(null));
        assertNull(ToolRouterMode.fromStored("  "));
        assertNull(ToolRouterMode.fromStored("SOME_FUTURE_MODE"));
    }

    @Test
    void toolRouterModeFollowsTheDetectedServerKind() {
        assertEquals(ToolRouterMode.FORMAT,
            ToolRouterMode.forServer(ToolCallCapabilityProbe.ServerKind.OLLAMA));
        assertEquals(ToolRouterMode.FORMAT,
            ToolRouterMode.forServer(ToolCallCapabilityProbe.ServerKind.LLAMA_CPP));
        assertEquals(ToolRouterMode.REQUIRED,
            ToolRouterMode.forServer(ToolCallCapabilityProbe.ServerKind.VLLM));
        assertNull(ToolRouterMode.forServer(ToolCallCapabilityProbe.ServerKind.OLLAMA_WITHOUT_FORMAT));
        assertNull(ToolRouterMode.forServer(ToolCallCapabilityProbe.ServerKind.OTHER));
        assertNull(ToolRouterMode.forServer(null));
    }

    // --- ToolCallGrade -------------------------------------------------------

    @Test
    void toolCallGradeFromStoredParsesCaseInsensitivelyAndToleratesUnknownText() {
        assertEquals(ToolCallGrade.STRUCTURED, ToolCallGrade.fromStored("structured"));
        assertEquals(ToolCallGrade.TEXT, ToolCallGrade.fromStored(" Text "));
        assertEquals(ToolCallGrade.NONE, ToolCallGrade.fromStored("NONE"));
        assertNull(ToolCallGrade.fromStored(null));
        assertNull(ToolCallGrade.fromStored(""));
        assertNull(ToolCallGrade.fromStored("UPGRADED_VALUE"));
    }

    // --- PlatformHostedLlmAuthType -------------------------------------------

    @Test
    void authTypeNormalizeCollapsesNullAndBlankToApiKey() {
        assertEquals(PlatformHostedLlmAuthType.API_KEY, PlatformHostedLlmAuthType.normalize(null));
        assertEquals(PlatformHostedLlmAuthType.API_KEY, PlatformHostedLlmAuthType.normalize("  "));
        assertEquals("CUSTOM_FUTURE", PlatformHostedLlmAuthType.normalize(" CUSTOM_FUTURE "),
            "an unrecognized stored value is trimmed, not rewritten - the row still loads");
        assertEquals("us-central1", PlatformHostedLlmAuthType.DEFAULT_VERTEX_LOCATION);
    }

    @Test
    void authTypeIsVertexCoversBothVertexKindsAndNothingElse() {
        assertTrue(PlatformHostedLlmAuthType.isVertex(PlatformHostedLlmAuthType.VERTEX_ADC));
        assertTrue(PlatformHostedLlmAuthType.isVertex(PlatformHostedLlmAuthType.VERTEX_SERVICE_ACCOUNT));
        assertFalse(PlatformHostedLlmAuthType.isVertex(PlatformHostedLlmAuthType.API_KEY));
        assertFalse(PlatformHostedLlmAuthType.isVertex(null));
        assertFalse(PlatformHostedLlmAuthType.isVertex("SOMETHING_ELSE"));
    }

    // --- ProviderHealth ------------------------------------------------------

    @Test
    void providerHealthUnknownForIsTheEmptyWindowShape() {
        ProviderHealth health = ProviderHealth.unknownFor(30);
        assertEquals(ProviderHealth.UNKNOWN, health.status());
        assertEquals(30, health.windowDays());
        assertEquals(0, health.failures());
        assertEquals(0, health.successes());
        assertNull(health.lastSuccessAt());
        assertNull(health.lastFailureAt());
        assertNull(health.lastError());
        assertNull(health.backgroundPausedUntil());
    }

    @Test
    void providerHealthRoundTripsItsComponents() {
        Instant success = Instant.parse("2026-10-01T00:00:00Z");
        Instant failure = Instant.parse("2026-10-02T00:00:00Z");
        Instant paused = Instant.parse("2026-10-03T00:00:00Z");
        ProviderHealth health =
            new ProviderHealth(ProviderHealth.REJECTED, 14, 3, 10, success, failure, "HTTP 401", paused);
        assertEquals(ProviderHealth.REJECTED, health.status());
        assertEquals(14, health.windowDays());
        assertEquals(3, health.failures());
        assertEquals(10, health.successes());
        assertEquals(success, health.lastSuccessAt());
        assertEquals(failure, health.lastFailureAt());
        assertEquals("HTTP 401", health.lastError());
        assertEquals(paused, health.backgroundPausedUntil());
        assertEquals("ok", ProviderHealth.OK);
    }
}
