package com.agenticai.core.providers.localmodels;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.providers.PlatformHostedLlmProvider;
import com.agenticai.core.providers.PlatformHostedLlmProviderRepository;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.providers.ToolCallCapabilityProbe;
import com.agenticai.core.security.ratelimit.LocalRateLimitFallback;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The {@code PUT /api/local-models/settings} runtime knobs (V674): {@code numBatch}, {@code
 * numParallel} (the {@code -parN} variant), {@code responseTimeoutMs} and {@code toolDeferral}.
 * Sibling of {@link LocalModelsApiTest} (same fixture shape; split out under the file-size
 * ratchet).
 */
class LocalModelsRuntimeKnobsTest {

    private static final Principal ADMIN =
            new TestingAuthenticationToken("admin-1", "n/a", "ROLE_tenant-admin");

    private FakeOllama fake;
    private LocalModelOllamaGateway gateway;
    private LocalModelPullService pulls;
    private MockMvc mvc;
    private final List<PlatformHostedLlmProvider> poolRows = new ArrayList<>();
    private PlatformHostedLlmProviderService poolService;

    @BeforeEach
    void setUp() throws Exception {
        LocalRateLimitFallback.reset();
        fake = new FakeOllama();
        gateway = new LocalModelOllamaGateway(fake.allowingGuard(), fake.baseUrl());
        pulls = new LocalModelPullService(gateway);
        poolService = mock(PlatformHostedLlmProviderService.class);
        HardwareDetector detector =
                new HardwareDetector(null) {
                    @Override
                    public HardwareProfile detect() {
                        return new HardwareProfile(16, 8, null, false);
                    }
                };
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        PlatformHostedLlmProviderRepository poolRepository =
                mock(PlatformHostedLlmProviderRepository.class);
        when(poolRepository.findAllByOrderByPriorityAsc())
                .thenAnswer(inv -> new ArrayList<>(poolRows));
        LocalModelsService service =
                new LocalModelsService(
                        detector,
                        new ModelCatalog(),
                        new ModelRecommender(),
                        gateway,
                        pulls,
                        beans.getBeanProvider(ToolCallCapabilityProbe.class),
                        poolService,
                        poolRepository,
                        false,
                        "Local model (Ollama)",
                        "ollama");
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new LocalModelsController(
                                        service, new LocalModelRateLimiter(60, 60, 60)))
                        .build();
    }

    @AfterEach
    void tearDown() {
        pulls.shutdown();
        gateway.close();
        fake.close();
        LocalRateLimitFallback.reset();
    }

    @Test
    void runtimeKnobsAreSavedOnTheServingRowAndValidated() throws Exception {
        PlatformHostedLlmProvider row =
                new PlatformHostedLlmProvider(
                        "Local", fake.baseUrl(), "qwen3.5:4b", "enc", true, 1, null, null);
        poolRows.add(row);

        mvc.perform(get("/api/local-models/settings").principal(ADMIN))
                .andExpect(jsonPath("$.numBatch").doesNotExist())
                .andExpect(jsonPath("$.numParallel").doesNotExist())
                .andExpect(jsonPath("$.responseTimeoutMs").doesNotExist())
                .andExpect(jsonPath("$.toolDeferral").doesNotExist());

        mvc.perform(
                        put("/api/local-models/settings")
                                .principal(ADMIN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"numBatch\":2048,\"responseTimeoutMs\":300000,\"toolDeferral\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numBatch").value(2048))
                .andExpect(jsonPath("$.responseTimeoutMs").value(300000))
                .andExpect(jsonPath("$.toolDeferral").value(true));
        org.junit.jupiter.api.Assertions.assertEquals(2048, row.getNumBatch());
        org.junit.jupiter.api.Assertions.assertEquals(300000L, row.getResponseTimeoutMs());
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, row.getToolDeferral());

        // 0 / false mean "back to the server or configured default": stored as null.
        mvc.perform(
                        put("/api/local-models/settings")
                                .principal(ADMIN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"numBatch\":0,\"responseTimeoutMs\":0,\"toolDeferral\":false}"))
                .andExpect(jsonPath("$.numBatch").doesNotExist())
                .andExpect(jsonPath("$.responseTimeoutMs").doesNotExist())
                .andExpect(jsonPath("$.toolDeferral").doesNotExist());
        org.junit.jupiter.api.Assertions.assertNull(row.getNumBatch());
        org.junit.jupiter.api.Assertions.assertNull(row.getResponseTimeoutMs());
        org.junit.jupiter.api.Assertions.assertNull(row.getToolDeferral());

        for (String bad : List.of("-1", "999999")) {
            mvc.perform(
                            put("/api/local-models/settings")
                                    .principal(ADMIN)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"numBatch\":" + bad + "}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(
                        put("/api/local-models/settings")
                                .principal(ADMIN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"responseTimeoutMs\":500}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aParallelVariantIsDerivedThroughApiCreateAndNamedInTheTag() throws Exception {
        org.junit.jupiter.api.Assertions.assertEquals(
                2, LocalModelsService.parallelOf("qwen3.5:4b-par2"));
        org.junit.jupiter.api.Assertions.assertEquals(
                4, LocalModelsService.parallelOf("qwen3.5:4b-ctx16k-par4"));
        org.junit.jupiter.api.Assertions.assertNull(LocalModelsService.parallelOf("qwen3.5:4b"));
        org.junit.jupiter.api.Assertions.assertEquals(
                "qwen3.5:4b", LocalModelsService.baseTag("qwen3.5:4b-ctx16k-par2"));
        org.junit.jupiter.api.Assertions.assertEquals(
                "qwen3.5:4b", LocalModelsService.baseTag("qwen3.5:4b-par4"));
        org.junit.jupiter.api.Assertions.assertEquals(
                16384, LocalModelsService.contextOf("qwen3.5:4b-ctx16k-par2"));

        fake.tagsJson = "{\"models\":[{\"name\":\"qwen3.5:4b\"}]}";
        // A mock with a mutable model: the entity has no setModel - the pool service is what
        // repoints the row after a variant is created.
        PlatformHostedLlmProvider row = LocalModelsApiTest.poolRow(1L, fake.baseUrl());
        java.util.concurrent.atomic.AtomicReference<String> model =
                new java.util.concurrent.atomic.AtomicReference<>("qwen3.5:4b");
        when(row.getModel()).thenAnswer(inv -> model.get());
        poolRows.add(row);
        when(poolService.updateModel(org.mockito.ArgumentMatchers.eq(1L), any()))
                .thenAnswer(
                        inv -> {
                            model.set(inv.getArgument(1));
                            return LocalModelsApiTest.poolResponse(
                                    1L, model.get(), true, "STRUCTURED");
                        });

        mvc.perform(
                        put("/api/local-models/settings")
                                .principal(ADMIN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"numParallel\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numParallel").value(2));
        org.junit.jupiter.api.Assertions.assertEquals("qwen3.5:4b-par2", model.get());
        org.junit.jupiter.api.Assertions.assertTrue(
                fake.createBodies.get(0).contains("num_parallel\":2"),
                fake.createBodies.toString());

        // A context variant keeps the parallel suffix: qwen3.5:4b-par2 -> qwen3.5:4b-ctx16k-par2.
        mvc.perform(
                        put("/api/local-models/settings")
                                .principal(ADMIN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"contextLength\":16384}"))
                .andExpect(jsonPath("$.contextLength").value(16384))
                .andExpect(jsonPath("$.numParallel").value(2));
        org.junit.jupiter.api.Assertions.assertEquals("qwen3.5:4b-ctx16k-par2", model.get());

        // Clearing parallelism drops the suffix.
        mvc.perform(
                        put("/api/local-models/settings")
                                .principal(ADMIN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"numParallel\":0}"))
                .andExpect(jsonPath("$.numParallel").doesNotExist())
                .andExpect(jsonPath("$.contextLength").value(16384));
        org.junit.jupiter.api.Assertions.assertEquals("qwen3.5:4b-ctx16k", model.get());

        mvc.perform(
                        put("/api/local-models/settings")
                                .principal(ADMIN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"numParallel\":3}"))
                .andExpect(status().isBadRequest());
    }
}
