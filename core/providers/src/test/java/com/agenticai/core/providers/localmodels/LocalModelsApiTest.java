package com.agenticai.core.providers.localmodels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.PlatformHostedLlmProvider;
import com.agenticai.core.providers.PlatformHostedLlmProviderRepository;
import com.agenticai.core.providers.PlatformHostedLlmProviderResponse;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.providers.PlatformHostedLlmProviderUpsertRequest;
import com.agenticai.core.providers.ToolCallCapabilityProbe;
import com.agenticai.core.providers.ToolCallCapabilityProbe.ServerKind;
import com.agenticai.core.providers.ToolCallGrade;
import com.agenticai.core.security.ratelimit.LocalRateLimitFallback;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The HTTP contract of {@code /api/local-models/*} against a fake Ollama and a stub probe. */
class LocalModelsApiTest {

    private static final Principal ADMIN = new TestingAuthenticationToken("admin-1", "n/a", "ROLE_tenant-admin");

    private FakeOllama fake;
    private LocalModelOllamaGateway gateway;
    private LocalModelPullService pulls;
    private StubProbe probe;
    private MockMvc mvc;
    private HardwareProfile hardware = new HardwareProfile(16, 8, null, false);
    private final List<PlatformHostedLlmProvider> poolRows = new ArrayList<>();
    private PlatformHostedLlmProviderService poolService;

    private static final class StubProbe implements ToolCallCapabilityProbe {
        Optional<Result> result = Optional.of(new Result(ToolCallGrade.STRUCTURED, ServerKind.OLLAMA, 4.0));
        boolean honours = true;
        String probedModel;
        String probedBase;

        @Override
        public Optional<Result> probe(String baseUrl, String completionsPath, String model, String apiKeyOrNull) {
            probedBase = baseUrl;
            probedModel = model;
            return result;
        }

        @Override
        public boolean honoursJsonSchema(String baseUrl, String completionsPath, String model, String apiKeyOrNull) {
            return honours;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        LocalRateLimitFallback.reset();
        fake = new FakeOllama();
        gateway = new LocalModelOllamaGateway(fake.allowingGuard(), fake.baseUrl());
        pulls = new LocalModelPullService(gateway);
        probe = new StubProbe();
        poolService = mock(PlatformHostedLlmProviderService.class);
        mvc = mvcWith(60, 60, 60, false, true);
    }

    @AfterEach
    void tearDown() {
        pulls.shutdown();
        gateway.close();
        fake.close();
        LocalRateLimitFallback.reset();
    }

    private MockMvc mvcWith(long reads, long probes, long pullsPerMinute, boolean uncataloged, boolean withProbe) {
        HardwareDetector detector = new HardwareDetector(null) {
            @Override
            public HardwareProfile detect() {
                return hardware;
            }
        };
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (withProbe) {
            beans.addBean("probe", probe);
        }
        PlatformHostedLlmProviderRepository poolRepository = mock(PlatformHostedLlmProviderRepository.class);
        when(poolRepository.findAllByOrderByPriorityAsc()).thenAnswer(inv -> new ArrayList<>(poolRows));
        LocalModelsService service = new LocalModelsService(
                detector,
                new ModelCatalog(),
                new ModelRecommender(),
                gateway,
                pulls,
                beans.getBeanProvider(ToolCallCapabilityProbe.class),
                poolService,
                poolRepository,
                uncataloged,
                "Local model (Ollama)",
                "ollama");
        return MockMvcBuilders.standaloneSetup(
                        new LocalModelsController(service, new LocalModelRateLimiter(reads, probes, pullsPerMinute)))
                .build();
    }

    // --- authorization ---

    @Test
    void theWholeSurfaceIsTenantAdminOnly() {
        PreAuthorize rule = LocalModelsController.class.getAnnotation(PreAuthorize.class);
        assertNotNull(rule, "the controller must carry @PreAuthorize");
        assertTrue(rule.value().contains("ROLE_tenant-admin"));
        assertTrue(rule.value().contains("ROLE_platform-admin"));
        assertTrue(!rule.value().contains("permitAll"));
    }

    // --- GET /recommendation ---

    @Test
    void recommendationHasExactlyTheContractShape() throws Exception {
        fake.tagsJson = "{\"models\":[{\"name\":\"qwen3.5:4b\"}]}";

        mvc.perform(get("/api/local-models/recommendation").principal(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hardware.ramGb").value(16.0))
                .andExpect(jsonPath("$.hardware.cpuCores").value(8))
                .andExpect(jsonPath("$.hardware.gpu").doesNotExist())
                .andExpect(jsonPath("$.hardware.appleSilicon").value(false))
                .andExpect(jsonPath("$.recommended.tag").value("qwen3.5:4b"))
                .andExpect(jsonPath("$.recommended.downloadGb").value(3.0))
                .andExpect(jsonPath("$.recommended.ramGb").value(10.0))
                .andExpect(jsonPath("$.recommended.toolCalling").value("native"))
                .andExpect(jsonPath("$.recommended.note").isNotEmpty())
                .andExpect(jsonPath("$.recommended.fits").value(true))
                .andExpect(jsonPath("$.recommended.family").value("Qwen"))
                .andExpect(jsonPath("$.recommended.paramsB").value(4.0))
                .andExpect(jsonPath("$.recommended.contextK").value(262))
                .andExpect(jsonPath("$.recommended.vramGb").value(5.0))
                .andExpect(jsonPath("$.recommended.licence").value("Apache-2.0"))
                .andExpect(jsonPath("$.recommended.thinking").value(true))
                .andExpect(jsonPath("$.recommended.bestFor").isNotEmpty())
                .andExpect(jsonPath("$.alternatives[0].tag").isNotEmpty())
                .andExpect(jsonPath("$.alternatives[0].toolCalling").isNotEmpty())
                .andExpect(jsonPath("$.budgetGb").value(12.0))
                .andExpect(jsonPath("$.catalog[0].tag").value("qwen3.5:2b"))
                .andExpect(jsonPath("$.catalog[?(@.tag == 'gpt-oss:120b')].fits").value(false))
                .andExpect(jsonPath("$.catalog[?(@.tag == 'qwen3.5:4b')].fits").value(true))
                .andExpect(jsonPath("$.ollama.reachable").value(true))
                .andExpect(jsonPath("$.ollama.baseUrl").value(fake.baseUrl()))
                .andExpect(jsonPath("$.ollama.installedTags[0]").value("qwen3.5:4b"));
    }

    @Test
    void gpuAndAppleSiliconSerialiseAsTheContractDescribes() throws Exception {
        hardware = new HardwareProfile(32, 12, new HardwareProfile.Gpu("Apple", "Apple M3 Pro", 32), true);

        mvc.perform(get("/api/local-models/recommendation").principal(ADMIN))
                .andExpect(jsonPath("$.hardware.appleSilicon").value(true))
                .andExpect(jsonPath("$.hardware.gpu.vendor").value("Apple"))
                .andExpect(jsonPath("$.hardware.gpu.name").value("Apple M3 Pro"))
                .andExpect(jsonPath("$.hardware.gpu.vramGb").value(32.0))
                .andExpect(jsonPath("$.recommended.tag").value("gpt-oss:20b"));
    }

    @Test
    void anUnreachableOllamaStillGetsARecommendation() throws Exception {
        fake.close();

        mvc.perform(get("/api/local-models/recommendation").principal(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ollama.reachable").value(false))
                .andExpect(jsonPath("$.ollama.installedTags").isEmpty())
                .andExpect(jsonPath("$.recommended.tag").value("qwen3.5:4b"));
    }

    // --- POST /probe ---

    @Test
    void probeReturnsTheMeasuredGradeAndTheRouterMode() throws Exception {
        fake.tagsJson = "{\"models\":[{\"name\":\"qwen3.5:4b\"}]}";

        mvc.perform(post("/api/local-models/probe")
                        .principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tag\":\"qwen3.5:4b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tag").value("qwen3.5:4b"))
                .andExpect(jsonPath("$.toolCalling").value("native"))
                .andExpect(jsonPath("$.structuredOutput").value(true))
                .andExpect(jsonPath("$.latencyMs").isNumber())
                .andExpect(jsonPath("$.toolRouterMode").value("off"));
        assertEquals("qwen3.5:4b", probe.probedModel);
        assertEquals(fake.baseUrl(), probe.probedBase, "only the configured Ollama is probed");
    }

    @Test
    void aSmallModelThatWritesToolCallsAsTextGetsTheConstrainedRouter() throws Exception {
        fake.tagsJson = "{\"models\":[{\"name\":\"llama3.2:3b\"}]}";
        probe.result = Optional.of(new ToolCallCapabilityProbe.Result(ToolCallGrade.TEXT, ServerKind.OLLAMA, 3.0));

        mvc.perform(post("/api/local-models/probe")
                        .principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tag\":\"llama3.2:3b\"}"))
                .andExpect(jsonPath("$.toolCalling").value("constrained"))
                .andExpect(jsonPath("$.toolRouterMode").value("FORMAT"));
    }

    @Test
    void anMlxRunnerThatRefusesJsonSchemaIsReportedAsNoStructuredOutput() throws Exception {
        fake.tagsJson = "{\"models\":[{\"name\":\"qwen3.5:4b\"}]}";
        probe.honours = false;

        mvc.perform(post("/api/local-models/probe")
                        .principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tag\":\"qwen3.5:4b\"}"))
                .andExpect(jsonPath("$.structuredOutput").value(false));

        probe.result = Optional.of(
                new ToolCallCapabilityProbe.Result(ToolCallGrade.NONE, ServerKind.OLLAMA_WITHOUT_FORMAT, 4.0));
        mvc.perform(post("/api/local-models/probe")
                        .principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tag\":\"qwen3.5:4b\"}"))
                .andExpect(jsonPath("$.toolCalling").value("none"))
                .andExpect(jsonPath("$.structuredOutput").value(false))
                .andExpect(jsonPath("$.toolRouterMode").value("off"));
    }

    @Test
    void probeRefusesBadTagsUninstalledModelsAndAnUnavailableProbe() throws Exception {
        fake.tagsJson = "{\"models\":[{\"name\":\"qwen3.5:4b\"}]}";
        for (String bad : List.of("", "../etc/passwd", "a b", "http://evil/x", "-x", "x".repeat(200))) {
            mvc.perform(post("/api/local-models/probe")
                            .principal(ADMIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tag\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/local-models/probe")
                        .principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tag\":\"qwen3.5:9b\"}"))
                .andExpect(status().isNotFound());

        probe.result = Optional.empty();
        mvc.perform(post("/api/local-models/probe")
                        .principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tag\":\"qwen3.5:4b\"}"))
                .andExpect(status().isBadGateway());

        MockMvc noProbe = mvcWith(60, 60, 60, false, false);
        noProbe.perform(post("/api/local-models/probe")
                        .principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tag\":\"qwen3.5:4b\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    // --- POST /pull and GET /pull/{id} ---

    @Test
    void pullAnswers202WithAJobIdAndThePollEndsDone() throws Exception {
        String body = mvc.perform(post("/api/local-models/pull")
                        .principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tag\":\"qwen3.5:2b\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String jobId = body.replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String last = "";
        while (!last.contains("\"done\"") && System.nanoTime() < deadline) {
            last = mvc.perform(get("/api/local-models/pull/" + jobId).principal(ADMIN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").isNotEmpty())
                    .andExpect(jsonPath("$.percent").isNumber())
                    .andExpect(jsonPath("$.message").isNotEmpty())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            Thread.sleep(20);
        }
        assertTrue(last.contains("\"done\"") && last.contains("\"percent\":100"), last);
        assertTrue(fake.pullBodies.get(0).contains("qwen3.5:2b"));
    }

    @Test
    void aTagOutsideTheCatalogIsRefusedUnlessTheOperatorAllowsIt() throws Exception {
        mvc.perform(post("/api/local-models/pull")
                        .principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tag\":\"someone/huge-model:405b\"}"))
                .andExpect(status().isBadRequest());
        assertTrue(fake.pullBodies.isEmpty());

        MockMvc open = mvcWith(60, 60, 60, true, true);
        open.perform(post("/api/local-models/pull")
                        .principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tag\":\"someone/huge-model:405b\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void pullWithOllamaDownIsServiceUnavailableAndAnUnknownJobIs404() throws Exception {
        fake.close();
        mvc.perform(post("/api/local-models/pull")
                        .principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tag\":\"qwen3.5:4b\"}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/local-models/pull/not-a-job").principal(ADMIN)).andExpect(status().isNotFound());
    }

    @Test
    void aBodyWithoutATagIsABadRequest() throws Exception {
        mvc.perform(post("/api/local-models/pull")
                        .principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // --- POST /use ---

    private static PlatformHostedLlmProviderResponse poolResponse(Long id, String model, boolean enabled, String grade) {
        return new PlatformHostedLlmProviderResponse(
                id, "Local model (Ollama)", "http://x", model, enabled, 5, null, null,
                null, null, null, null, false, "API_KEY", null, null,
                true, grade, null, null, null, null, null, false);
    }

    private static PlatformHostedLlmProvider poolRow(Long id, String baseUrl) {
        PlatformHostedLlmProvider row = mock(PlatformHostedLlmProvider.class);
        when(row.getId()).thenReturn(id);
        when(row.getBaseUrl()).thenReturn(baseUrl);
        return row;
    }

    @Test
    void settingsAreReadFromAndSavedOnTheServingRow() throws Exception {
        // A real entity, not the mock poolRow gives: the endpoint writes the row's setters.
        PlatformHostedLlmProvider row =
                new PlatformHostedLlmProvider("Local", fake.baseUrl(), "qwen3.5:4b", "enc", true, 1, null, null);
        poolRows.add(row);

        mvc.perform(get("/api/local-models/settings").principal(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servingModel").value("qwen3.5:4b"))
                .andExpect(jsonPath("$.reasoningEffort").value("none"))
                .andExpect(jsonPath("$.keepAlive").value("30m"));

        mvc.perform(put("/api/local-models/settings")
                        .principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasoningEffort\":\"high\",\"keepAlive\":\"2h\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reasoningEffort").value("high"))
                .andExpect(jsonPath("$.keepAlive").value("2h"));
        org.junit.jupiter.api.Assertions.assertEquals("high", row.getReasoningEffort());
        org.junit.jupiter.api.Assertions.assertEquals("2h", row.getKeepAlive());

        // "default" means the model's own behaviour: stored as blank, shown as default.
        mvc.perform(put("/api/local-models/settings")
                        .principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasoningEffort\":\"default\"}"))
                .andExpect(jsonPath("$.reasoningEffort").value("default"));
        org.junit.jupiter.api.Assertions.assertEquals("", row.getReasoningEffort());

        mvc.perform(put("/api/local-models/settings")
                        .principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasoningEffort\":\"turbo\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/local-models/settings")
                        .principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keepAlive\":\"forever\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void contextVariantsAreNamedAfterTheBaseTagAndRefuseUnknownSizes() throws Exception {
        org.junit.jupiter.api.Assertions.assertEquals("qwen3.5:4b", LocalModelsService.baseTag("qwen3.5:4b-ctx16k"));
        org.junit.jupiter.api.Assertions.assertEquals("qwen3.5:4b", LocalModelsService.baseTag("qwen3.5:4b"));
        org.junit.jupiter.api.Assertions.assertEquals(16384, LocalModelsService.contextOf("qwen3.5:4b-ctx16k"));
        org.junit.jupiter.api.Assertions.assertNull(LocalModelsService.contextOf("llama3.2:3b"));

        poolRows.add(new PlatformHostedLlmProvider("Local", fake.baseUrl(), "qwen3.5:4b", "enc", true, 1, null, null));
        mvc.perform(get("/api/local-models/settings").principal(ADMIN))
                .andExpect(jsonPath("$.contextLength").doesNotExist());
        mvc.perform(put("/api/local-models/settings")
                        .principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contextLength\":12345}"))
                .andExpect(status().isBadRequest());
        // A size the row already has changes nothing and needs no model server round trip.
        mvc.perform(put("/api/local-models/settings")
                        .principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contextLength\":0}"))
                .andExpect(status().isOk());
    }

    @Test
    void settingsWithNoLocalModelRowAreNotFound() throws Exception {
        mvc.perform(put("/api/local-models/settings")
                        .principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasoningEffort\":\"low\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void useRepointsThePoolRowServingThisServer() throws Exception {
        fake.tagsJson = "{\"models\":[{\"name\":\"qwen3.5:4b\"},{\"name\":\"deepseek-r1:8b\"}]}";
        poolRows.add(poolRow(7L, fake.baseUrl()));
        when(poolService.updateModel(7L, "deepseek-r1:8b"))
                .thenReturn(poolResponse(7L, "deepseek-r1:8b", true, "STRUCTURED"));

        mvc.perform(post("/api/local-models/use")
                        .principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tag\":\"deepseek-r1:8b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tag").value("deepseek-r1:8b"))
                .andExpect(jsonPath("$.rowId").value(7))
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.toolCallGrade").value("STRUCTURED"));
        verify(poolService).updateModel(7L, "deepseek-r1:8b");
    }

    @Test
    void useCreatesThePoolRowWhenNonePointsAtThisServer() throws Exception {
        fake.tagsJson = "{\"models\":[{\"name\":\"gpt-oss:20b\"}]}";
        when(poolService.createAllowingBlankKey(any(PlatformHostedLlmProviderUpsertRequest.class)))
                .thenAnswer(inv -> {
                    PlatformHostedLlmProviderUpsertRequest req = inv.getArgument(0);
                    assertEquals("gpt-oss:20b", req.model());
                    assertEquals(fake.baseUrl(), req.baseUrl());
                    assertEquals(Boolean.TRUE, req.zeroCost());
                    return poolResponse(9L, req.model(), true, "TEXT");
                });

        mvc.perform(post("/api/local-models/use")
                        .principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tag\":\"gpt-oss:20b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.rowId").value(9));
        verify(poolService).createAllowingBlankKey(any(PlatformHostedLlmProviderUpsertRequest.class));
    }

    @Test
    void useRefusesModelsThatAreNotInstalled() throws Exception {
        fake.tagsJson = "{\"models\":[{\"name\":\"qwen3.5:4b\"}]}";

        mvc.perform(post("/api/local-models/use")
                        .principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tag\":\"qwen3.5:9b\"}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(poolService);
    }

    @Test
    void useWithTheServerDownIsServiceUnavailable() throws Exception {
        fake.close();

        mvc.perform(post("/api/local-models/use")
                        .principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tag\":\"qwen3.5:4b\"}"))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(poolService);
    }

    @Test
    void recommendationReportsTheModelThePoolCurrentlyServes() throws Exception {
        fake.tagsJson = "{\"models\":[{\"name\":\"qwen3.5:4b\"}]}";
        poolRows.add(poolRow(7L, fake.baseUrl() + "/v1")); // the /v1 suffix must not hide the match
        PlatformHostedLlmProvider row = poolRows.get(0);
        when(row.getModel()).thenReturn("deepseek-r1:8b");

        mvc.perform(get("/api/local-models/recommendation").principal(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servingModel").value("deepseek-r1:8b"));
    }

    // --- rate limiting ---

    @Test
    void probesPullsAndReadsAreRateLimitedPerCaller() throws Exception {
        MockMvc tight = mvcWith(2, 1, 1, false, true);
        fake.tagsJson = "{\"models\":[{\"name\":\"qwen3.5:4b\"}]}";

        tight.perform(post("/api/local-models/probe").principal(ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tag\":\"qwen3.5:4b\"}")).andExpect(status().isOk());
        tight.perform(post("/api/local-models/probe").principal(ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tag\":\"qwen3.5:4b\"}")).andExpect(status().isTooManyRequests());
        // another admin has their own bucket
        tight.perform(post("/api/local-models/probe").principal(new TestingAuthenticationToken("admin-2", "n/a", "ROLE_tenant-admin")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tag\":\"qwen3.5:4b\"}")).andExpect(status().isOk());

        tight.perform(post("/api/local-models/pull").principal(ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tag\":\"qwen3.5:2b\"}")).andExpect(status().isAccepted());
        tight.perform(post("/api/local-models/pull").principal(ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tag\":\"qwen3.5:2b\"}")).andExpect(status().isTooManyRequests());

        tight.perform(get("/api/local-models/recommendation").principal(ADMIN)).andExpect(status().isOk());
        tight.perform(get("/api/local-models/recommendation").principal(ADMIN)).andExpect(status().isOk());
        tight.perform(get("/api/local-models/recommendation").principal(ADMIN)).andExpect(status().isTooManyRequests());
        tight.perform(get("/api/local-models/recommendation").principal(ADMIN))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hardware"))));
    }
}
