package com.agenticai.core.agents.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.PlatformToolCredentialService;
import com.agenticai.core.providers.PlatformToolKey;
import com.agenticai.core.providers.capability.ProviderCredentialService;
import com.agenticai.core.providers.capability.ProviderKeys;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

/**
 * {@link JevDecisionService} coverage - same MockRestServiceServer harness as the Sarvam client
 * tests. The contract that matters here is the FAIL-OPEN half: every upstream failure mode
 * (4xx/5xx, malformed body, missing key) must degrade to {@code Optional.empty()}/empty map rather
 * than throw, because every caller is a hot path that falls back to a local heuristic.
 */
class JevDecisionServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockRestServiceServer server;
    private PlatformToolCredentialService credentials;
    private ProviderCredentialService providerCredentials;
    private JevDecisionService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        credentials = mock(PlatformToolCredentialService.class);
        providerCredentials = mock(ProviderCredentialService.class);
        // Zero-TTL cache = pass-through, so each test controls key resolution
        // per-call rather than inheriting a cached answer from the last test.
        service =
                new JevDecisionService(
                        builder.build(),
                        credentials,
                        providerCredentials,
                        true,
                        "env-key",
                        "https://api.typesafe.ai",
                        "jev-latest",
                        Duration.ZERO);
        when(credentials.resolveActiveApiKey(PlatformToolKey.TYPESAFE))
                .thenReturn(Optional.empty());
        when(providerCredentials.resolveDecryptedSecret(ProviderKeys.TYPESAFE))
                .thenReturn(Optional.empty());
    }

    @AfterEach
    void clearTenant() {
        // TenantContext is a ThreadLocal - any test that set one must not leak
        // it into the next test's resolution.
        TenantContext.clear();
    }

    @Test
    void unavailableWhenNoKeyResolves() {
        service =
                new JevDecisionService(
                        RestClient.builder().build(),
                        credentials,
                        providerCredentials,
                        true,
                        "",
                        "https://api.typesafe.ai",
                        "jev-latest",
                        Duration.ZERO);

        assertThat(service.isAvailable()).isFalse();
        assertThat(service.noul("state", "question?")).isEmpty();
    }

    @Test
    void unavailableWhenDisabledEvenWithKey() {
        service =
                new JevDecisionService(
                        RestClient.builder().build(),
                        credentials,
                        providerCredentials,
                        false,
                        "env-key",
                        "https://api.typesafe.ai",
                        "jev-latest",
                        Duration.ZERO);

        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    void noulSendsTypedQuestionAndParsesProbability() {
        server.expect(MockRestRequestMatchers.requestTo("https://api.typesafe.ai/v1/systemone"))
                .andExpect(MockRestRequestMatchers.header("Authorization", "Bearer env-key"))
                .andExpect(MockRestRequestMatchers.jsonPath("$.model").value("jev-latest"))
                .andExpect(MockRestRequestMatchers.jsonPath("$.state").value("is this simple?"))
                .andExpect(MockRestRequestMatchers.jsonPath("$.questions.q.type").value("noul"))
                .andRespond(
                        MockRestResponseCreators.withSuccess(
                                "{\"model\":\"jev-1.13.0\",\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":0.83}},\"usage\":{}}",
                                MediaType.APPLICATION_JSON));

        assertThat(service.noul("is this simple?", "Could a small model answer this?"))
                .hasValue(0.83);
        server.verify();
    }

    @Test
    void managedCredentialKeyWinsOverEnvKey() {
        when(credentials.resolveActiveApiKey(PlatformToolKey.TYPESAFE))
                .thenReturn(Optional.of("managed-key"));
        server.expect(MockRestRequestMatchers.requestTo("https://api.typesafe.ai/v1/systemone"))
                .andExpect(MockRestRequestMatchers.header("Authorization", "Bearer managed-key"))
                .andRespond(
                        MockRestResponseCreators.withSuccess(
                                "{\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":0.5}}}",
                                MediaType.APPLICATION_JSON));

        assertThat(service.noul("s", "q?")).hasValue(0.5);
    }

    @Test
    void noulsBatchesQuestionsInOneCallAndMapsOnlyValidAnswers() {
        Map<String, String> questions = new LinkedHashMap<>();
        questions.put("a", "first?");
        questions.put("b", "second?");
        server.expect(MockRestRequestMatchers.requestTo("https://api.typesafe.ai/v1/systemone"))
                .andExpect(MockRestRequestMatchers.jsonPath("$.questions.a.type").value("noul"))
                .andExpect(MockRestRequestMatchers.jsonPath("$.questions.b.type").value("noul"))
                .andRespond(
                        MockRestResponseCreators.withSuccess(
                                // "b" comes back with a foreign answer type - a defensive
                                // parse must skip it rather than fabricate a 0.0 probability.
                                "{\"answers\":{\"a\":{\"type\":\"noul\",\"noul\":0.9},\"b\":{\"type\":\"choice\",\"choice\":\"x\"}}}",
                                MediaType.APPLICATION_JSON));

        Map<String, Double> out = service.nouls("state", questions);

        assertThat(out).containsEntry("a", 0.9).doesNotContainKey("b");
    }

    @Test
    void chooseReturnsWinnerProbabilitiesAndConfidence() {
        Map<String, String> criteria = new LinkedHashMap<>();
        criteria.put("id-1", "Translator - translates text between languages");
        criteria.put("id-2", "Support Triage - answers customer questions");
        server.expect(MockRestRequestMatchers.requestTo("https://api.typesafe.ai/v1/systemone"))
                .andExpect(MockRestRequestMatchers.jsonPath("$.questions.q.type").value("choice"))
                .andExpect(MockRestRequestMatchers.jsonPath("$.questions.q.criteria.id-1").exists())
                .andRespond(
                        MockRestResponseCreators.withSuccess(
                                "{\"answers\":{\"q\":{\"type\":\"choice\",\"choice\":\"id-1\","
                                        + "\"probabilities\":{\"id-1\":0.81,\"id-2\":0.19},\"confidence\":0.9}}}",
                                MediaType.APPLICATION_JSON));

        Optional<DecisionModel.Choice> choice =
                service.choose("translate this", "Which agent?", criteria);

        assertThat(choice).isPresent();
        assertThat(choice.get().choice()).isEqualTo("id-1");
        assertThat(choice.get().probabilities()).containsEntry("id-1", 0.81);
        assertThat(choice.get().confidence()).isEqualTo(0.9);
    }

    @Test
    void chooseEmptyWhenWinnerIsNotAnOfferedOption() {
        // A choice the caller can't map back to a real option is worse than
        // no answer - the caller must get the fallback signal, not a phantom.
        server.expect(MockRestRequestMatchers.requestTo("https://api.typesafe.ai/v1/systemone"))
                .andRespond(
                        MockRestResponseCreators.withSuccess(
                                "{\"answers\":{\"q\":{\"type\":\"choice\",\"choice\":\"not-offered\",\"probabilities\":{}}}}",
                                MediaType.APPLICATION_JSON));

        assertThat(service.choose("s", "q?", Map.of("a", "A", "b", "B"))).isEmpty();
    }

    @Test
    void scoreParsesWeightedScoreAndDistribution() {
        server.expect(MockRestRequestMatchers.requestTo("https://api.typesafe.ai/v1/systemone"))
                .andExpect(MockRestRequestMatchers.jsonPath("$.questions.q.type").value("score"))
                .andRespond(
                        MockRestResponseCreators.withSuccess(
                                "{\"answers\":{\"q\":{\"type\":\"score\",\"score\":1.6,"
                                        + "\"probabilities\":{\"0\":0.2,\"1\":0.3,\"2\":0.5},\"confidence\":0.78}}}",
                                MediaType.APPLICATION_JSON));

        Optional<DecisionModel.Score> score =
                service.score("state", "Rate complexity", List.of("trivial", "moderate", "hard"));

        assertThat(score).isPresent();
        assertThat(score.get().score()).isEqualTo(1.6);
        assertThat(score.get().probabilities()).containsEntry("2", 0.5);
    }

    @Test
    void httpErrorFailsOpenToEmpty() {
        for (int i = 0; i < 2; i++) {
            server.expect(MockRestRequestMatchers.requestTo("https://api.typesafe.ai/v1/systemone"))
                    .andRespond(
                            MockRestResponseCreators.withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .body("{\"error\":\"boom\"}"));
        }

        assertThat(service.noul("s", "q?")).isEmpty();
        assertThat(service.choose("s", "q?", Map.of("a", "A", "b", "B"))).isEmpty();
    }

    @Test
    void malformedBodyFailsOpenToEmpty() {
        server.expect(MockRestRequestMatchers.requestTo("https://api.typesafe.ai/v1/systemone"))
                .andRespond(
                        MockRestResponseCreators.withSuccess(
                                "{\"unexpected\":true}", MediaType.APPLICATION_JSON));

        assertThat(service.noul("s", "q?")).isEmpty();
    }

    @Test
    void emptyQuestionMapMakesNoHttpCall() {
        assertThat(service.nouls("state", Map.of())).isEmpty();
        assertThat(service.choose("s", "q?", Map.of())).isEmpty();
        // server.verify() would fail if ANY request had gone out.
    }

    // --- Tenant BYOK resolution (provider_credential override) ------------

    @Test
    void tenantKeyWinsOverPlatformAndEnvWhenContextPresent() {
        TenantContext.set(TenantContext.of("tenant-1", "user", Set.of()));
        when(providerCredentials.resolveDecryptedSecret(ProviderKeys.TYPESAFE))
                .thenReturn(Optional.of("tenant-key"));
        when(credentials.resolveActiveApiKey(PlatformToolKey.TYPESAFE))
                .thenReturn(Optional.of("managed-key"));
        server.expect(MockRestRequestMatchers.requestTo("https://api.typesafe.ai/v1/systemone"))
                .andExpect(MockRestRequestMatchers.header("Authorization", "Bearer tenant-key"))
                .andRespond(
                        MockRestResponseCreators.withSuccess(
                                "{\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":0.5}}}",
                                MediaType.APPLICATION_JSON));

        assertThat(service.noul("s", "q?")).hasValue(0.5);
        assertThat(service.keySource()).hasValue(DecisionModel.KeySource.TENANT);
    }

    @Test
    void platformKeyUsedWhenTenantContextHasNoCredential() {
        TenantContext.set(TenantContext.of("tenant-1", "user", Set.of()));
        when(credentials.resolveActiveApiKey(PlatformToolKey.TYPESAFE))
                .thenReturn(Optional.of("managed-key"));
        server.expect(MockRestRequestMatchers.requestTo("https://api.typesafe.ai/v1/systemone"))
                .andExpect(MockRestRequestMatchers.header("Authorization", "Bearer managed-key"))
                .andRespond(
                        MockRestResponseCreators.withSuccess(
                                "{\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":0.5}}}",
                                MediaType.APPLICATION_JSON));

        assertThat(service.noul("s", "q?")).hasValue(0.5);
        assertThat(service.keySource()).hasValue(DecisionModel.KeySource.PLATFORM);
    }

    @Test
    void tenantCredentialIsNotConsultedWithoutTenantContext() {
        // No TenantContext on the thread -> the env key wins and the tenant
        // credential store is never touched at all.
        assertThat(service.keySource()).hasValue(DecisionModel.KeySource.ENV);
        verify(providerCredentials, never()).resolveDecryptedSecret(anyString());
    }

    // --- Playground (fail-visible, bounded pass-through) -------------------

    @Test
    void playgroundReturnsAnswersUsageAndSource() {
        server.expect(MockRestRequestMatchers.requestTo("https://api.typesafe.ai/v1/systemone"))
                .andExpect(MockRestRequestMatchers.jsonPath("$.questions.q.type").value("choice"))
                .andExpect(
                        MockRestRequestMatchers.jsonPath("$.questions.q.criteria.a")
                                .value("Option A"))
                .andRespond(
                        MockRestResponseCreators.withSuccess(
                                "{\"model\":\"jev-1.13.0\",\"answers\":{\"q\":{\"type\":\"choice\",\"choice\":\"a\"}},"
                                        + "\"usage\":{\"input_tokens\":120}}",
                                MediaType.APPLICATION_JSON));

        JsonNode question =
                MAPPER.createObjectNode()
                        .put("type", "choice")
                        .put("instructions", "pick one")
                        .set(
                                "criteria",
                                MAPPER.createObjectNode()
                                        .put("a", "Option A")
                                        .put("b", "Option B"));
        Optional<DecisionModel.PlaygroundOutcome> out =
                service.playground("some state", Map.of("q", question));

        assertThat(out).isPresent();
        assertThat(out.get().error()).isNull();
        assertThat(out.get().keySource()).isEqualTo(DecisionModel.KeySource.ENV);
        assertThat(out.get().model()).isEqualTo("jev-1.13.0");
        assertThat(out.get().answers().path("q").path("choice").asText()).isEqualTo("a");
        assertThat(out.get().usage().path("input_tokens").asInt()).isEqualTo(120);
    }

    @Test
    void playgroundSurfacesUpstreamErrorInsteadOfFailingOpen() {
        server.expect(MockRestRequestMatchers.requestTo("https://api.typesafe.ai/v1/systemone"))
                .andRespond(
                        MockRestResponseCreators.withStatus(HttpStatus.UNAUTHORIZED)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body("{\"error\":\"bad key\"}"));

        JsonNode question =
                MAPPER.createObjectNode().put("type", "noul").put("instructions", "yes or no?");
        Optional<DecisionModel.PlaygroundOutcome> out =
                service.playground("state", Map.of("q", question));

        // A tenant experimenting must SEE the failure - this is the contract
        // difference from the internal noul/choose paths, which would have
        // returned empty on this same 401.
        assertThat(out).isPresent();
        assertThat(out.get().error()).contains("401");
        assertThat(out.get().errorStatus()).isEqualTo(401);
        assertThat(out.get().answers()).isNull();
    }

    @Test
    void playgroundEmptyWhenNoKeyConfigured() {
        service =
                new JevDecisionService(
                        RestClient.builder().build(),
                        credentials,
                        providerCredentials,
                        true,
                        "",
                        "https://api.typesafe.ai",
                        "jev-latest",
                        Duration.ZERO);
        JsonNode question =
                MAPPER.createObjectNode().put("type", "noul").put("instructions", "yes or no?");

        assertThat(service.playground("state", Map.of("q", question))).isEmpty();
    }

    @Test
    void playgroundRejectsInvalidQuestionShapes() {
        JsonNode badType =
                MAPPER.createObjectNode()
                        .put("type", "essay")
                        .put("instructions", "write me a poem");
        assertThatThrownBy(() -> service.playground("state", Map.of("q", badType)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported type");

        JsonNode noInstructions = MAPPER.createObjectNode().put("type", "noul");
        assertThatThrownBy(() -> service.playground("state", Map.of("q", noInstructions)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instructions");

        JsonNode oneOption =
                MAPPER.createObjectNode()
                        .put("type", "choice")
                        .put("instructions", "pick")
                        .set("criteria", MAPPER.createObjectNode().put("only", "one"));
        assertThatThrownBy(() -> service.playground("state", Map.of("q", oneOption)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("criteria");
    }
}
