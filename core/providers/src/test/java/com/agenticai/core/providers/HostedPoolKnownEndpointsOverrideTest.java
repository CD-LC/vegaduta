package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The hosted pool's blank-base-URL default comes from {@code app.llm.endpoints}, not a second literal list. */
class HostedPoolKnownEndpointsOverrideTest {

    @Test
    void theDefaultMapStillHoldsTheLiteralsItAlwaysDid() {
        Map<ProviderName, HostedPoolKnownEndpoints.KnownEndpoint> known = HostedPoolKnownEndpoints.KNOWN_PROVIDER_ENDPOINTS;

        assertEquals("https://api.openai.com", known.get(ProviderName.OPENAI).baseUrl());
        assertEquals("https://api.groq.com/openai", known.get(ProviderName.GROQ).baseUrl());
        assertEquals("/v1beta/openai/chat/completions", known.get(ProviderName.GEMINI).completionsPath());
        assertNull(known.get(ProviderName.META).completionsPath());
        assertTrue(known.keySet().containsAll(PlatformHostedLlmProviderService.HOSTED_POOL_COMPATIBLE_PROVIDERS));
    }

    @Test
    void anOverrideChangesTheDefaultAndSarvamStaysOutOfThePool() {
        ProviderEndpoints endpoints = new ProviderEndpoints();
        endpoints.getMistral().setBaseUrl("https://eu.mistral.example");

        Map<ProviderName, HostedPoolKnownEndpoints.KnownEndpoint> known = HostedPoolKnownEndpoints.from(endpoints);

        assertEquals("https://eu.mistral.example", known.get(ProviderName.MISTRAL).baseUrl());
        assertEquals("https://api.openai.com", known.get(ProviderName.OPENAI).baseUrl());
        assertTrue(!known.containsKey(ProviderName.SARVAM), "SARVAM is not hosted-pool compatible");
    }
}
