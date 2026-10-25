package com.agenticai.core.agents.tools.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeDtos.CreateRecipeRequest;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeDtos.RecipeFromRunRequest;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeDtos.RecipeSummary;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeDtos.RunRecipeRequest;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeDtos.UpdateRecipeRequest;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeSpec.RecipeStep;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.secrets.SecretVaultService;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The flag gate and the lookup contract: off means management calls 404 and runtime lookups
 * return nothing; on, an ambiguous recipe name refuses and a missing sidecar is a clean
 * failure outcome, never a crash.
 */
class DesktopRecipeServiceTest {

    private DesktopRecipeRepository recipes;
    private ObjectProvider<AgentService> agents;
    private ObjectProvider<ToolCallLogRepository> journal;
    private ObjectProvider<SecretVaultService> vault;
    private ObjectProvider<DesktopToolSource> desktop;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        recipes = mock(DesktopRecipeRepository.class);
        agents = mock(ObjectProvider.class);
        journal = mock(ObjectProvider.class);
        vault = mock(ObjectProvider.class);
        desktop = mock(ObjectProvider.class);
        when(agents.getIfAvailable()).thenReturn(null);
        when(journal.getIfAvailable()).thenReturn(null);
        when(vault.getIfAvailable()).thenReturn(null);
        when(desktop.getIfAvailable()).thenReturn(null);
        TenantContext.set(TenantContext.of("t1", "user", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private DesktopRecipeService service(boolean enabled) {
        return new DesktopRecipeService(
                recipes, agents, journal, vault, desktop,
                enabled, 50, 15_000, 120, 800, "DESKTOP_");
    }

    private static void assertNotFound(Runnable r) {
        assertThatThrownBy(r::run)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void flagOffAnswers404EverywhereAndLooksUpNothing() {
        DesktopRecipeService off = service(false);
        UUID id = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        assertThat(off.isEnabled()).isFalse();
        assertNotFound(() -> off.list(agentId));
        assertNotFound(() -> off.get(id));
        assertNotFound(
                () ->
                        off.create(
                                new CreateRecipeRequest(
                                        agentId, "n", null, null, null)));
        assertNotFound(
                () ->
                        off.saveFromRun(
                                new RecipeFromRunRequest(
                                        agentId, "s", "n", null, Map.of(), false, null)));
        assertNotFound(() -> off.update(id, new UpdateRecipeRequest("n", null, null)));
        assertNotFound(() -> off.delete(id));
        assertNotFound(() -> off.run(id, new RunRecipeRequest(Map.of(), true, 1, null)));

        assertThat(off.listRunnable(agentId)).isEmpty();
        assertThat(off.findRunnable(agentId, "x")).isEmpty();
        assertThat(off.replayForAgent(agentId, "x", Map.of(), false, 1, false).success())
                .isFalse();
        off.recordRun(id, "failure"); // silently a no-op, like the browser twin
    }

    private static DesktopRecipeSpec validSpec() throws Exception {
        RecipeStep activate =
                new RecipeStep(
                        DesktopRecipeSpec.ACTIVATE, "notes", null, null, null, null, null, null,
                        null, null, null, null, false);
        return new DesktopRecipeSpec(
                DesktopRecipeSpec.CURRENT_VERSION, List.of("notes"), List.of(), List.of(activate));
    }

    private DesktopRecipe stored(UUID agentId, String name) throws Exception {
        DesktopRecipeSpec spec = validSpec();
        return new DesktopRecipe(
                agentId,
                name,
                null,
                spec.allowedApps(),
                "AUTO",
                JsonMappers.plain().writeValueAsString(spec),
                spec.steps().size(),
                "user",
                java.time.Instant.now());
    }

    @Test
    void anAmbiguousNameRefusesAndSaysToUseTheId() throws Exception {
        DesktopRecipeService on = service(true);
        UUID agentId = UUID.randomUUID();
        DesktopRecipe mine = stored(agentId, "Morning");
        DesktopRecipe shared = stored(null, "morning"); // same name, tenant-wide scope
        when(recipes.findByAgentIdOrAgentIdIsNullOrderByNameAsc(agentId))
                .thenReturn(List.of(mine, shared));

        assertThatThrownBy(() -> on.findRunnable(agentId, "Morning"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than one");
    }

    @Test
    void replayForAgentReportsAMissingRecipeInsideTheOutcome() {
        DesktopRecipeService on = service(true);
        UUID agentId = UUID.randomUUID();
        when(recipes.findByAgentIdOrAgentIdIsNullOrderByNameAsc(agentId))
                .thenReturn(List.of());

        DesktopRecipeReplayer.Outcome o =
                on.replayForAgent(agentId, "nope", Map.of(), false, 1, false);
        assertThat(o.success()).isFalse();
        assertThat(o.failure()).contains("nope");
    }

    @Test
    void replayForAgentFailsCleanlyWhenTheSidecarIsOff() throws Exception {
        DesktopRecipeService on = service(true);
        UUID agentId = UUID.randomUUID();
        DesktopRecipe recipe = stored(agentId, "Morning");
        when(recipes.findByAgentIdOrAgentIdIsNullOrderByNameAsc(agentId))
                .thenReturn(List.of(recipe));

        DesktopRecipeReplayer.Outcome o =
                on.replayForAgent(agentId, "morning", Map.of(), false, 1, false);
        assertThat(o.success()).isFalse();
        assertThat(o.failure()).contains("desktop agent");
    }

    @Test
    void replayForAgentUsesTheSharedSidecarClient() throws Exception {
        List<String> calls = new java.util.ArrayList<>();
        DesktopRecipeService on =
                new DesktopRecipeService(
                        recipes, agents, journal, vault, desktop,
                        true, 50, 15_000, 120, 800, "DESKTOP_") {
                    @Override
                    DesktopRecipeReplayer.Executor executor() {
                        return (path, body) -> {
                            calls.add(path);
                            return JsonMappers.plain().readTree("{\"ok\":true}");
                        };
                    }
                };
        UUID agentId = UUID.randomUUID();
        DesktopRecipe recipe = stored(agentId, "Morning");
        when(recipes.findByAgentIdOrAgentIdIsNullOrderByNameAsc(agentId))
                .thenReturn(List.of(recipe));

        DesktopRecipeReplayer.Outcome o =
                on.replayForAgent(agentId, "morning", Map.of(), false, 1, false);
        assertThat(o.success()).isTrue();
        assertThat(calls).containsExactly("/activate");
    }

    @Test
    void listReturnsSummaries() throws Exception {
        DesktopRecipeService on = service(true);
        UUID agentId = UUID.randomUUID();
        AgentService agentService = mock(AgentService.class);
        when(agents.getIfAvailable()).thenReturn(agentService);
        DesktopRecipe recipe = stored(agentId, "Morning");
        when(recipes.findByAgentIdOrAgentIdIsNullOrderByNameAsc(agentId))
                .thenReturn(List.of(recipe));

        List<RecipeSummary> out = on.list(agentId);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).name()).isEqualTo("Morning");
        assertThat(out.get(0).allowedApps()).containsExactly("notes");
        assertThat(out.get(0).defaultMode()).isEqualTo("AUTO");
    }

    @Test
    void findRunnableById() throws Exception {
        DesktopRecipeService on = service(true);
        UUID agentId = UUID.randomUUID();
        DesktopRecipe recipe = stored(agentId, "Morning");
        when(recipes.findByAgentIdOrAgentIdIsNullOrderByNameAsc(agentId))
                .thenReturn(List.of(recipe));

        assertThat(on.findRunnable(agentId, recipe.getId().toString()).orElseThrow())
                .isSameAs(recipe);
        assertThat(on.findRunnable(agentId, UUID.randomUUID().toString())).isEmpty();
        assertThat(on.findRunnable(agentId, "morning").orElseThrow()).isSameAs(recipe);
    }

    @Test
    void storeRejectsASecretOutsideTheDesktopPrefix() throws Exception {
        DesktopRecipeService on = service(true);
        UUID agentId = UUID.randomUUID();
        AgentService agentService = mock(AgentService.class);
        when(agents.getIfAvailable()).thenReturn(agentService);
        RecipeStep activate =
                new RecipeStep(DesktopRecipeSpec.ACTIVATE, "notes", null, null, null, null,
                        null, null, null, null, null, null, false);
        RecipeStep type =
                new RecipeStep(DesktopRecipeSpec.TYPE, null, null, null, null, null,
                        "{{secret.OTHER_PW}}", null, null, null, null, null, false);
        DesktopRecipeSpec spec =
                new DesktopRecipeSpec(
                        DesktopRecipeSpec.CURRENT_VERSION, List.of("notes"), List.of(),
                        List.of(activate, type));
        when(recipes.existsByAgentIdAndNameIgnoreCase(any(), any())).thenReturn(false);
        when(recipes.count()).thenReturn(0L);

        assertThatThrownBy(
                        () ->
                                on.create(
                                        new CreateRecipeRequest(
                                                agentId, "n", null, null, spec)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DESKTOP_");
    }
}
