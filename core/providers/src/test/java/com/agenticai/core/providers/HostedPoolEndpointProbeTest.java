package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import com.agenticai.core.providers.validation.ProviderValidationException;
import com.agenticai.core.security.net.SsrfGuard;
import java.net.InetAddress;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link HostedPoolEndpointProbe}'s URL shaping is pure static logic (a trailing {@code /v1} is
 * stripped so it never doubles into {@code /v1/v1/chat/completions}; a custom {@code
 * completionsPath} swaps its {@code /chat/completions} tail for {@code /models}), and the two live
 * probes go through an ordinary {@code RestClient} a bound {@code MockRestServiceServer} can serve.
 */
class HostedPoolEndpointProbeTest {

    /** Public-address resolution without real DNS, for the guard discoverModels consults. */
    private static SsrfGuard publicGuard() {
        return SsrfGuard.of(
                Set.of(),
                host ->
                        "10.0.0.5".equals(host)
                                ? new InetAddress[] {InetAddress.getByName("10.0.0.5")}
                                : new InetAddress[] {InetAddress.getByName("93.184.216.34")});
    }

    // --- normalizeBaseUrl / effectiveBaseUrl ---------------------------------

    @Test
    void normalizeBaseUrlStripsExactlyOneTrailingV1() {
        assertNull(HostedPoolEndpointProbe.normalizeBaseUrl(null));
        assertEquals(
                "https://x.example",
                HostedPoolEndpointProbe.normalizeBaseUrl("https://x.example/v1"));
        assertEquals(
                "https://x.example",
                HostedPoolEndpointProbe.normalizeBaseUrl("https://x.example/v1/"));
        assertEquals(
                "https://x.example",
                HostedPoolEndpointProbe.normalizeBaseUrl("  https://x.example  "));
        assertEquals(
                "https://x.example/v1/v1",
                HostedPoolEndpointProbe.normalizeBaseUrl("https://x.example/v1/v1/v1"),
                "only ONE trailing /v1 is stripped - a doubled one is the bug being prevented");
        assertEquals(
                "https://x.example/v10",
                HostedPoolEndpointProbe.normalizeBaseUrl("https://x.example/v10"));
    }

    @Test
    void effectiveBaseUrlSkipsNormalizationForACustomCompletionsPath() {
        assertEquals(
                "https://x.example/v1",
                HostedPoolEndpointProbe.effectiveBaseUrl(
                        "https://x.example/v1", "/v1beta/openai/chat/completions"),
                "a custom-path row keeps its base URL verbatim");
        assertEquals(
                "https://x.example",
                HostedPoolEndpointProbe.effectiveBaseUrl("https://x.example/v1", null));
        assertNull(HostedPoolEndpointProbe.effectiveBaseUrl(null, null));
    }

    @Test
    void trimToNullCollapsesBlankAndTrims() {
        assertNull(HostedPoolEndpointProbe.trimToNull(null));
        assertNull(HostedPoolEndpointProbe.trimToNull("   "));
        assertEquals("x", HostedPoolEndpointProbe.trimToNull(" x "));
    }

    // --- deriveModelsUrl ------------------------------------------------------

    @Test
    void deriveModelsUrlSwapsTheCompletionsTailForModels() {
        assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/openai/models",
                HostedPoolEndpointProbe.deriveModelsUrl(
                        "https://generativelanguage.googleapis.com",
                        "/v1beta/openai/chat/completions"));
        assertEquals(
                "https://x.example/v1/models",
                HostedPoolEndpointProbe.deriveModelsUrl(
                        "https://x.example/", "/v1/chat/completions"));
        assertNull(
                HostedPoolEndpointProbe.deriveModelsUrl("https://x.example", "/custom/path"),
                "an unusual custom path means no probe is possible");
    }

    // --- validateKey ----------------------------------------------------------

    @Test
    void validateKeyProbesTheConventionalModelsUrlWithTheBearerKey() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HostedPoolEndpointProbe probe =
                new HostedPoolEndpointProbe(
                        builder.build(), HostedPoolEndpointProbeTest::publicGuard);
        server.expect(requestTo("https://api.groq.com/openai/v1/models"))
                .andExpect(header("Authorization", "Bearer key-1"))
                .andRespond(withSuccess());
        assertDoesNotThrow(() -> probe.validateKey("https://api.groq.com/openai", " key-1 ", null));
    }

    @Test
    void validateKeySendsNoAuthorizationHeaderForABlankKey() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HostedPoolEndpointProbe probe =
                new HostedPoolEndpointProbe(
                        builder.build(), HostedPoolEndpointProbeTest::publicGuard);
        server.expect(requestTo("http://local.example/v1/models")).andRespond(withSuccess());
        assertDoesNotThrow(() -> probe.validateKey("http://local.example", " ", null));
    }

    @Test
    void validateKeySkipsTheProbeForAnUnprobeableCustomPath() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HostedPoolEndpointProbe probe =
                new HostedPoolEndpointProbe(
                        builder.build(), HostedPoolEndpointProbeTest::publicGuard);
        assertDoesNotThrow(
                () -> probe.validateKey("https://x.example", "k", "/totally/custom/path"));
        server.verify(); // nothing was requested
    }

    @Test
    void validateKeyFailsOnARejectedProbeAndOnAnUnreachableHost() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HostedPoolEndpointProbe probe =
                new HostedPoolEndpointProbe(
                        builder.build(), HostedPoolEndpointProbeTest::publicGuard);
        server.expect(requestTo("https://api.groq.com/openai/v1/models"))
                .andRespond(withUnauthorizedRequest());
        ProviderValidationException rejected =
                assertThrows(
                        ProviderValidationException.class,
                        () -> probe.validateKey("https://api.groq.com/openai", "bad", null));
        assertTrue(rejected.getMessage().contains("HTTP 401"), rejected.getMessage());

        // A closed loopback port is refused immediately - no real network needed.
        HostedPoolEndpointProbe realProbe =
                new HostedPoolEndpointProbe(
                        RestClient.builder().build(), HostedPoolEndpointProbeTest::publicGuard);
        ProviderValidationException unreachable =
                assertThrows(
                        ProviderValidationException.class,
                        () -> realProbe.validateKey("http://127.0.0.1:1", "k", null));
        assertTrue(unreachable.getMessage().contains("Could not reach"), unreachable.getMessage());
    }

    // --- discoverModels --------------------------------------------------------

    @Test
    void discoverModelsReturnsTheIdsFromTheDataArray() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HostedPoolEndpointProbe probe =
                new HostedPoolEndpointProbe(
                        builder.build(), HostedPoolEndpointProbeTest::publicGuard);
        server.expect(requestTo("https://api.groq.com/openai/v1/models"))
                .andExpect(header("Authorization", "Bearer k"))
                .andRespond(
                        withSuccess(
                                "{\"data\":[{\"id\":\"llama-3.1-8b-instant\"},{\"id\":\"\"},{\"no_id\":1}]}",
                                MediaType.APPLICATION_JSON));
        assertEquals(
                List.of("llama-3.1-8b-instant"),
                probe.discoverModels("https://api.groq.com/openai", "k", null));
    }

    @Test
    void discoverModelsDerivesTheModelsUrlForACustomPathRow() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HostedPoolEndpointProbe probe =
                new HostedPoolEndpointProbe(
                        builder.build(), HostedPoolEndpointProbeTest::publicGuard);
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/openai/models"))
                .andRespond(
                        withSuccess(
                                "{\"data\":[{\"id\":\"gemini-2.5-flash\"}]}",
                                MediaType.APPLICATION_JSON));
        assertEquals(
                List.of("gemini-2.5-flash"),
                probe.discoverModels(
                        "https://generativelanguage.googleapis.com",
                        "k",
                        "/v1beta/openai/chat/completions"));
    }

    @Test
    void discoverModelsFailsSoftOnAGuardRefusalAndOnAMissingDataArray() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HostedPoolEndpointProbe probe =
                new HostedPoolEndpointProbe(
                        builder.build(), HostedPoolEndpointProbeTest::publicGuard);

        // An IP literal is classified by the guard without any DNS lookup: a
        // private address is refused before the transport, and discovery
        // degrades to an empty list rather than throwing.
        assertEquals(List.of(), probe.discoverModels("http://10.0.0.5", "k", null));

        server.expect(requestTo("https://api.groq.com/openai/v1/models"))
                .andRespond(withSuccess("{\"nope\":[]}", MediaType.APPLICATION_JSON));
        assertEquals(List.of(), probe.discoverModels("https://api.groq.com/openai", "k", null));
    }
}
