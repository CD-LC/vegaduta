package com.agenticai.core.agents.demo.magic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.demo.magic.DemoMagicPlanner.MagicIntent;
import com.agenticai.core.agents.demo.magic.DemoMagicPlanner.MagicPlan;
import com.agenticai.core.mcp.registry.OfficialMcpRegistryClient;
import com.agenticai.core.mcp.registry.RegistrySearchResponse;
import com.agenticai.core.mcp.registry.RegistrySearchResult;
import com.agenticai.core.providers.PlatformHostedLlmActiveResponse;
import com.agenticai.core.providers.PlatformHostedLlmProviderResponse;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Intent routing, live-pool model selection, registry discovery and the reveal's plan shape. */
class DemoMagicPlannerTest {

    private OfficialMcpRegistryClient registry;
    private PlatformHostedLlmProviderService pool;
    private DemoMagicPlanner planner;

    @BeforeEach
    void setUp() {
        registry = mock(OfficialMcpRegistryClient.class);
        pool = mock(PlatformHostedLlmProviderService.class);
        planner = new DemoMagicPlanner(registry, pool);
        when(registry.search(anyString(), any()))
                .thenReturn(new RegistrySearchResponse(List.of(), null, false));
        when(pool.getActiveSummary())
                .thenReturn(new PlatformHostedLlmActiveResponse(true, "Primary", "m-1"));
        rows(row("Primary", "m-1", true, 1));
    }

    private void rows(PlatformHostedLlmProviderResponse... rows) {
        List<PlatformHostedLlmProviderResponse> list = List.of(rows);
        when(pool.list()).thenReturn(list);
    }

    private static PlatformHostedLlmProviderResponse row(
            String label, String model, boolean enabled, int priority) {
        PlatformHostedLlmProviderResponse r = mock(PlatformHostedLlmProviderResponse.class);
        when(r.label()).thenReturn(label);
        when(r.model()).thenReturn(model);
        when(r.enabled()).thenReturn(enabled);
        when(r.priority()).thenReturn(priority);
        return r;
    }

    private static RegistrySearchResult hit(
            String name, String display, String description, Map<String, String> env) {
        RegistrySearchResult r = mock(RegistrySearchResult.class);
        when(r.name()).thenReturn(name);
        when(r.displayName()).thenReturn(display);
        when(r.description()).thenReturn(description);
        when(r.runnable()).thenReturn(true);
        when(r.repositoryUrl()).thenReturn("https://github.com/x/" + name);
        List<com.agenticai.core.mcp.registry.RegistryEnvVarSpec> vars = null;
        if (env != null) {
            vars = new ArrayList<>();
            for (int i = 0; i < env.size(); i++) {
                vars.add(mock(com.agenticai.core.mcp.registry.RegistryEnvVarSpec.class));
            }
        }
        when(r.envVars()).thenReturn(vars);
        return r;
    }

    @Test
    void theWordingPicksTheIntentInPriorityOrder() {
        assertThat(planner.classify("make a video poster", null)).isEqualTo(MagicIntent.VIDEO);
        assertThat(planner.classify("a poster for my bakery", null)).isEqualTo(MagicIntent.IMAGE);
        assertThat(planner.classify("summarise this", null)).isEqualTo(MagicIntent.RESEARCH);
        assertThat(planner.classify("hello", "https://example.com"))
                .isEqualTo(MagicIntent.RESEARCH);
        assertThat(planner.classify("see https://example.com/a", null))
                .isEqualTo(MagicIntent.RESEARCH);
        assertThat(planner.classify("write a caption", " ")).isEqualTo(MagicIntent.WRITE);
        assertThat(planner.classify("hello there", null)).isEqualTo(MagicIntent.GENERAL);
        assertThat(planner.classify(null, null)).isEqualTo(MagicIntent.GENERAL);
    }

    @Test
    void anImageIntentWithAnImageModelRoutesToThePlatformImagePool() {
        MagicPlan plan = planner.plan("a logo for my shop", null, "img-model");

        assertThat(plan.intent()).isEqualTo(MagicIntent.IMAGE);
        assertThat(plan.model().label()).isEqualTo("Platform image pool");
        assertThat(plan.model().model()).isEqualTo("img-model");
        assertThat(plan.honestyNote()).contains("hosted image model");
        assertThat(plan.formSpec())
                .extracting(DemoMagicPlanner.FormField::id)
                .containsExactly("prompt");
        assertThat(plan.mermaid()).contains("Image generation");
        verify(registry).search("image generation", null);
    }

    @Test
    void theTextPoolShowsThePrimaryRowAndUpToFourFailoversInPriorityOrder() {
        rows(
                row("C", "m-3", true, 3),
                row("Off", "m-x", false, 0),
                row("A", "m-1", true, 1),
                row("B", null, true, 2),
                row(null, "m-4", true, 4),
                row("D", "m-5", true, 5),
                row("E", "m-6", true, 6));

        MagicPlan plan = planner.plan("hello there", null, null);

        assertThat(plan.model().available()).isTrue();
        assertThat(plan.model().label()).isEqualTo("A");
        assertThat(plan.model().fallbacks())
                .containsExactly("B", "C · m-3", "pool row · m-4", "D · m-5");
        assertThat(plan.mermaid()).contains("(+4 failover)");
        assertThat(plan.steps().get(2).detail()).contains("automatic failover");
    }

    @Test
    void aPrimaryRowWithoutLabelOrModelGetsPlaceholders() {
        rows(row(null, null, true, 1));

        MagicPlan plan = planner.plan("hello there", null, null);

        assertThat(plan.model().label()).isEqualTo("Platform hosted pool");
        assertThat(plan.model().model()).isEqualTo("platform-pool");
    }

    @Test
    void anEmptyRowListingFallsBackToTheSummaryAndAnUnavailablePoolIsSaidSo() {
        rows();
        assertThat(planner.plan("hello there", null, null).model().model()).isEqualTo("m-1");

        when(pool.getActiveSummary())
                .thenReturn(new PlatformHostedLlmActiveResponse(true, null, null));
        assertThat(planner.plan("hello there", null, null).model().label())
                .isEqualTo("Platform hosted pool");

        when(pool.getActiveSummary()).thenReturn(PlatformHostedLlmActiveResponse.unavailable());
        MagicPlan down = planner.plan("hello there", null, null);
        assertThat(down.model().available()).isFalse();
        assertThat(down.steps().get(2).detail()).contains("unavailable");

        when(pool.getActiveSummary()).thenThrow(new IllegalStateException("db down"));
        assertThat(planner.plan("hello there", null, null).model().available()).isFalse();
    }

    @Test
    void registryHitsBecomePlannedToolsWithFreeMeaningNoCredentials() {
        String longText = "d".repeat(300);
        List<RegistrySearchResult> hits =
                List.of(
                        hit("fetch", "Fetch", "Fetches pages", Map.of()),
                        hit("raw-name", " ", longText, Map.of("API_KEY", "x")),
                        hit("nullenv", null, null, null),
                        hit("four", "Four", "d", Map.of()),
                        hit("five", "Five", "d", Map.of()),
                        hit("six", "Six", "d", Map.of()));
        when(registry.search("web search fetch", null))
                .thenReturn(new RegistrySearchResponse(hits, null, false));

        MagicPlan plan = planner.plan("compare these companies", null, null);

        assertThat(plan.registryUnavailable()).isFalse();
        assertThat(plan.tools()).hasSize(5);
        assertThat(plan.tools().get(0).free()).isTrue();
        assertThat(plan.tools().get(1).name()).isEqualTo("raw-name");
        assertThat(plan.tools().get(1).free()).isFalse();
        assertThat(plan.tools().get(1).description()).hasSize(180).endsWith("...");
        assertThat(plan.tools().get(2).free()).isTrue();
        assertThat(plan.tools().get(2).description()).isNull();
        assertThat(plan.steps().get(1).detail()).contains("5 candidate");
        assertThat(plan.mermaid()).contains("5 server(s)");
    }

    @Test
    void anUnavailableOrFailingRegistryIsReportedNotHidden() {
        when(registry.search(anyString(), any()))
                .thenReturn(new RegistrySearchResponse(List.of(), null, true));
        MagicPlan unavailable = planner.plan("hello there", null, null);
        assertThat(unavailable.registryUnavailable()).isTrue();
        assertThat(unavailable.steps().get(1).detail()).contains("no directly usable");
        assertThat(unavailable.mermaid()).contains("no match");

        when(registry.search(anyString(), any())).thenReturn(null);
        assertThat(planner.plan("hello there", null, null).registryUnavailable()).isTrue();

        when(registry.search(anyString(), any())).thenThrow(new IllegalStateException("boom"));
        assertThat(planner.plan("hello there", null, null).registryUnavailable()).isTrue();
    }

    @Test
    void aGeneralPromptSearchesTheRegistryByItsLongerWords() {
        planner.plan("Please book tickets, for the Mumbai concert!", null, null);
        verify(registry).search("please book tickets mumbai concert", null);

        planner.plan("a b c", null, null);
        verify(registry).search("assistant", null);
    }

    @Test
    void theVideoPlanAdmitsThereIsNoRenderingServer() {
        MagicPlan plan = planner.plan("make a reel about my cafe", "https://cafe.example", null);

        assertThat(plan.intent()).isEqualTo(MagicIntent.VIDEO);
        assertThat(plan.honestyNote()).contains("storyboard and script");
        assertThat(plan.agentSystemPrompt()).contains("storyboard director");
        assertThat(plan.formSpec()).extracting(DemoMagicPlanner.FormField::id).contains("link");
        assertThat(plan.mermaid())
                .contains("no free video MCP yet")
                .contains("Knowledge: public page scrape");
        assertThat(plan.steps().get(4).detail()).contains("page behind the link");
        assertThat(plan.steps().get(5).detail()).contains("storyboard");
    }

    @Test
    void titlesAreTrimmedCollapsedAndClippedAndAnEmptyPromptKeepsTheBaseTitle() {
        assertThat(planner.plan("  write   a   bio  ", null, null).title())
                .isEqualTo("Writing assistant — write a bio");
        assertThat(planner.plan("write " + "x".repeat(100), null, null).title())
                .endsWith("...")
                .hasSize("Writing assistant — ".length() + 60);
        MagicPlan blank = planner.plan(null, null, null);
        assertThat(blank.title()).isEqualTo("Custom assistant");
        assertThat(blank.agentSystemPrompt()).contains("general assistance");
        assertThat(blank.honestyNote()).isNull();
    }

    @Test
    void everyIntentGetsItsOwnAgentFormAndPrompt() {
        assertThat(planner.plan("research this", null, null).agentName())
                .isEqualTo("Research analyst agent");
        assertThat(planner.plan("research this", null, null).agentSystemPrompt())
                .contains("never invent facts");
        assertThat(planner.plan("write an email", null, null).agentName())
                .isEqualTo("Copywriter agent");
        assertThat(planner.plan("write an email", null, null).formSpec().get(0).label())
                .contains("written");
        assertThat(planner.plan("hello there", null, null).agentName())
                .isEqualTo("Workflow assistant agent");
        assertThat(planner.plan("a banner", null, null).agentName())
                .isEqualTo("Visual creator agent");
        assertThat(planner.plan("research this", null, null).formSpec()).hasSize(2);
        verify(registry, org.mockito.Mockito.atLeastOnce())
                .search(contains("web search"), isNull());
    }
}
