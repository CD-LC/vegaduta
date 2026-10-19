package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.tools.browseruse.BrowserAutomationDtos.CreateRecipeRequest;
import com.agenticai.core.agents.tools.browseruse.BrowserAutomationDtos.RecipeDetail;
import com.agenticai.core.agents.tools.browseruse.BrowserAutomationDtos.RecipeFromRunRequest;
import com.agenticai.core.agents.tools.browseruse.BrowserAutomationDtos.UpdateRecipeRequest;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipeSpec.RecipeStep;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

/**
 * Saving a run as a recipe from the tool-call journal, hand-created recipes, access rules and
 * flag-off identity.
 */
class BrowserRecipeServiceTest {

    private static final UUID AGENT = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");

    private final Map<UUID, BrowserRecipe> store = new HashMap<>();
    private BrowserRecipeRepository repository;
    private AgentService agentService;
    private ToolCallLogRepository journal;
    private BrowserRecipeService service;

    @BeforeEach
    void setUp() {
        repository = mock(BrowserRecipeRepository.class);
        agentService = mock(AgentService.class);
        journal = mock(ToolCallLogRepository.class);
        when(repository.save(any(BrowserRecipe.class)))
                .thenAnswer(
                        inv -> {
                            BrowserRecipe r = inv.getArgument(0);
                            store.put(r.getId(), r);
                            return r;
                        });
        when(repository.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.<UUID>getArgument(0))));
        when(repository.count()).thenAnswer(inv -> (long) store.size());
        when(repository.findAllByOrderByNameAsc())
                .thenAnswer(inv -> new ArrayList<>(store.values()));
        when(repository.existsByAgentIdAndNameIgnoreCase(any(UUID.class), anyString()))
                .thenAnswer(
                        inv ->
                                store.values().stream()
                                        .anyMatch(
                                                r ->
                                                        inv.<UUID>getArgument(0)
                                                                        .equals(r.getAgentId())
                                                                && r.getName()
                                                                        .equalsIgnoreCase(
                                                                                inv
                                                                                        .<String>
                                                                                                getArgument(
                                                                                                        1))));
        when(repository.existsByAgentIdIsNullAndNameIgnoreCase(anyString()))
                .thenAnswer(
                        inv ->
                                store.values().stream()
                                        .anyMatch(
                                                r ->
                                                        r.getAgentId() == null
                                                                && r.getName()
                                                                        .equalsIgnoreCase(
                                                                                inv
                                                                                        .<String>
                                                                                                getArgument(
                                                                                                        0))));
        when(repository.findByAgentIdOrAgentIdIsNullOrderByNameAsc(any(UUID.class)))
                .thenAnswer(
                        inv ->
                                store.values().stream()
                                        .filter(
                                                r ->
                                                        r.getAgentId() == null
                                                                || r.getAgentId()
                                                                        .equals(
                                                                                inv
                                                                                        .<UUID>
                                                                                                getArgument(
                                                                                                        0)))
                                        .toList());
        service = newService(true);
        asManager();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private BrowserRecipeService newService(boolean enabled) {
        ObjectProvider<AgentService> agents = mock(ObjectProvider.class);
        when(agents.getIfAvailable()).thenReturn(agentService);
        ObjectProvider<ToolCallLogRepository> journals = mock(ObjectProvider.class);
        when(journals.getIfAvailable()).thenReturn(journal);
        return new BrowserRecipeService(
                repository,
                agents,
                journals,
                enabled,
                3,
                15_000,
                120,
                "",
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static void asManager() {
        TenantContext.set(TenantContext.of("tenant-1", "alice", Set.of("PERM_MANAGE_AGENTS")));
    }

    private static void asMember() {
        TenantContext.set(TenantContext.of("tenant-1", "bob", Set.of()));
    }

    private static ToolCallLog call(String tool, String input, String output) {
        return new ToolCallLog(AGENT, "session-1", tool, input, output, null, NOW, NOW);
    }

    private void journalOf(ToolCallLog... rows) {
        when(journal.findByAgentIdAndSessionIdOrderByStartedAtAsc(AGENT, "session-1"))
                .thenReturn(List.of(rows));
    }

    private void agentAccessible() {
        Agent agent = mock(Agent.class);
        when(agentService.requireAccess(eq(AGENT), any(PermissionLevel.class))).thenReturn(agent);
    }

    private static RecipeFromRunRequest fromRun(
            String name, Map<String, String> params, Boolean allowLiteral) {
        return new RecipeFromRunRequest(
                AGENT, "session-1", name, "Search the shop", params, allowLiteral);
    }

    private void aRunThatSearches() {
        journalOf(
                call(
                        "browser_navigate",
                        "{\"url\":\"https://shop.example.com/search?q=blue%20widget\"}",
                        "{\"url\":\"https://shop.example.com/search?q=blue%20widget\",\"title\":\"Search\"}"),
                call(
                        "browser_type",
                        "{\"selector\":\"#filter\",\"text\":\"blue widget\"}",
                        "{\"typedInto\":\"#filter\"}"),
                call("browser_click", "{\"selector\":\"Apply\"}", "{\"clicked\":\"Apply\"}"));
    }

    // ---- save from run
    // ---------------------------------------------------------------------------

    @Test
    void savesTheRunAsAPlaceholderOnlyRecipeAndNeverStoresTheSuppliedValue() {
        agentAccessible();
        aRunThatSearches();

        RecipeDetail saved =
                service.saveFromRun(fromRun("Search shop", Map.of("query", "blue widget"), false));

        assertThat(saved.summary().name()).isEqualTo("Search shop");
        assertThat(saved.summary().agentId()).isEqualTo(AGENT);
        assertThat(saved.summary().stepCount()).isEqualTo(3);
        assertThat(saved.summary().parameters()).containsExactly("query");
        assertThat(saved.spec().steps())
                .extracting(RecipeStep::action)
                .containsExactly("navigate", "type", "click");
        BrowserRecipe row = store.values().iterator().next();
        assertThat(row.getRecipeJson())
                .contains("{{query}}")
                .doesNotContain("blue widget")
                .doesNotContain("blue%20widget");
        assertThat(row.getAllowedDomainList()).containsExactly("shop.example.com");
        verify(agentService).requireAccess(AGENT, PermissionLevel.EDITOR);
    }

    @Test
    void aRunWithNoJournalRowsOrWithAnUnparameterizedLiteralIsRefused() {
        agentAccessible();
        journalOf();
        assertThatThrownBy(() -> service.saveFromRun(fromRun("Empty", Map.of(), false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no tool calls");

        aRunThatSearches();
        assertThatThrownBy(() -> service.saveFromRun(fromRun("Literal", Map.of(), false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("step 2");
        assertThat(store).isEmpty();
    }

    @Test
    void savingNeedsEditorAccessToTheAgent() {
        when(agentService.requireAccess(eq(AGENT), any(PermissionLevel.class)))
                .thenThrow(new ResourceAccessDeniedException());
        aRunThatSearches();

        assertThatThrownBy(
                        () ->
                                service.saveFromRun(
                                        fromRun("x", Map.of("query", "blue widget"), false)))
                .isInstanceOf(ResourceAccessDeniedException.class);
        assertThat(store).isEmpty();
    }

    @Test
    void nameConflictsAndTheTenantCapAreReported() {
        agentAccessible();
        aRunThatSearches();
        service.saveFromRun(fromRun("Search shop", Map.of("query", "blue widget"), false));

        assertThatThrownBy(
                        () ->
                                service.saveFromRun(
                                        fromRun(
                                                "search SHOP",
                                                Map.of("query", "blue widget"),
                                                false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");

        service.saveFromRun(fromRun("Second", Map.of("query", "blue widget"), false));
        service.saveFromRun(fromRun("Third", Map.of("query", "blue widget"), false));
        assertThatThrownBy(
                        () ->
                                service.saveFromRun(
                                        fromRun("Fourth", Map.of("query", "blue widget"), false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("maximum of 3");
    }

    // ---- hand-created and managed recipes
    // --------------------------------------------------------

    private static BrowserRecipeSpec simpleSpec() {
        return new BrowserRecipeSpec(
                1,
                List.of("shop.example.com"),
                List.of(),
                List.of(
                        new RecipeStep(
                                "navigate",
                                "https://shop.example.com/",
                                null,
                                null,
                                null,
                                null,
                                null,
                                false)));
    }

    @Test
    void aHandWrittenRecipeIsValidatedLikeARecordedOne() {
        BrowserRecipeSpec literalPassword =
                new BrowserRecipeSpec(
                        1,
                        List.of("shop.example.com"),
                        List.of(),
                        List.of(
                                new RecipeStep(
                                        "navigate",
                                        "https://shop.example.com/",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false),
                                new RecipeStep(
                                        "type",
                                        null,
                                        "input[type=password]",
                                        "hunter2",
                                        null,
                                        null,
                                        null,
                                        false)));

        assertThatThrownBy(
                        () ->
                                service.create(
                                        new CreateRecipeRequest(
                                                null, "Bad", null, literalPassword)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never a literal");
        assertThat(store).isEmpty();

        RecipeDetail created =
                service.create(
                        new CreateRecipeRequest(null, "Home", "Open the shop", simpleSpec()));
        assertThat(created.summary().agentId()).isNull();
        assertThat(service.listRunnable(AGENT)).hasSize(1);
        assertThat(service.findRunnable(AGENT, "home")).isPresent();
        assertThat(service.findRunnable(AGENT, created.summary().id().toString())).isPresent();
        assertThat(service.findRunnable(UUID.randomUUID(), "missing")).isEmpty();
    }

    @Test
    void aTenantWideRecipeNeedsTheManageAgentsPermission() {
        asMember();

        assertThatThrownBy(
                        () ->
                                service.create(
                                        new CreateRecipeRequest(null, "Home", null, simpleSpec())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void anAgentScopedRecipeIsManagedByEditorsAndReadByRunners() {
        agentAccessible();
        RecipeDetail created =
                service.create(new CreateRecipeRequest(AGENT, "Mine", null, simpleSpec()));
        asMember();

        assertThat(service.list(AGENT)).hasSize(1);
        verify(agentService).requireAccess(AGENT, PermissionLevel.RUNNER);
        assertThat(service.get(created.summary().id()).spec().steps()).hasSize(1);
        service.update(created.summary().id(), new UpdateRecipeRequest("Renamed", "d"));
        assertThat(store.get(created.summary().id()).getName()).isEqualTo("Renamed");
        verify(agentService, org.mockito.Mockito.atLeastOnce())
                .requireAccess(AGENT, PermissionLevel.EDITOR);
        // a member without the manage permission sees only tenant-wide recipes when no agent is
        // named
        assertThat(service.list(null)).isEmpty();
    }

    @Test
    void recordRunKeepsTheLatestFailureAndCounts() {
        RecipeDetail created =
                service.create(new CreateRecipeRequest(null, "Home", null, simpleSpec()));
        UUID id = created.summary().id();

        service.recordRun(id, "step 2: verification failed");
        service.recordRun(id, null);

        BrowserRecipe row = store.get(id);
        assertThat(row.getRunCount()).isEqualTo(2);
        assertThat(row.getFailCount()).isEqualTo(1);
        assertThat(row.getLastFailure()).isNull();
        assertThat(row.getLastRunAt()).isEqualTo(NOW);
    }

    // ---- secret naming
    // ---------------------------------------------------------------------------

    @Test
    void recipesMayOnlyTypeVaultSecretsNamedForBrowserUseWhenAPrefixIsConfigured() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentService> agents = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolCallLogRepository> journals = mock(ObjectProvider.class);
        BrowserRecipeService strict =
                new BrowserRecipeService(
                        repository,
                        agents,
                        journals,
                        true,
                        10,
                        15_000,
                        120,
                        "BROWSER_",
                        Clock.fixed(NOW, ZoneOffset.UTC));
        BrowserRecipeSpec other =
                new BrowserRecipeSpec(
                        1,
                        List.of("shop.example.com"),
                        List.of(),
                        List.of(
                                new RecipeStep(
                                        "navigate",
                                        "https://shop.example.com/",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false),
                                new RecipeStep(
                                        "type",
                                        null,
                                        "input[type=password]",
                                        "{{secret.OPENAI_API_KEY}}",
                                        null,
                                        null,
                                        null,
                                        false)));
        BrowserRecipeSpec named =
                new BrowserRecipeSpec(
                        1,
                        List.of("shop.example.com"),
                        List.of(),
                        List.of(
                                new RecipeStep(
                                        "navigate",
                                        "https://shop.example.com/",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false),
                                new RecipeStep(
                                        "type",
                                        null,
                                        "input[type=password]",
                                        "{{secret.BROWSER_SHOP_PW}}",
                                        null,
                                        null,
                                        null,
                                        false),
                                new RecipeStep(
                                        "type",
                                        null,
                                        "#otp",
                                        "{{totp.BROWSER_SHOP_2FA}}",
                                        null,
                                        null,
                                        null,
                                        false)));

        assertThatThrownBy(() -> strict.create(new CreateRecipeRequest(null, "Other", null, other)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OPENAI_API_KEY")
                .hasMessageContaining("BROWSER_");
        assertThat(store).isEmpty();
        assertThat(
                        strict.create(new CreateRecipeRequest(null, "Named", null, named))
                                .summary()
                                .stepCount())
                .isEqualTo(3);
    }

    // ---- flag-off identity
    // -----------------------------------------------------------------------

    @Test
    void withTheFlagOffEveryCallAnswers404OrNothingAndNoRepositoryIsTouched() {
        BrowserRecipeRepository untouched = mock(BrowserRecipeRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentService> agents = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolCallLogRepository> journals = mock(ObjectProvider.class);
        BrowserRecipeService off =
                new BrowserRecipeService(
                        untouched,
                        agents,
                        journals,
                        false,
                        50,
                        15_000,
                        120,
                        "",
                        Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(off.isEnabled()).isFalse();
        assertThat(off.listRunnable(AGENT)).isEmpty();
        assertThat(off.findRunnable(AGENT, "anything")).isEmpty();
        off.recordRun(UUID.randomUUID(), "x");
        assertThatThrownBy(() -> off.list(null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        assertThatThrownBy(() -> off.create(new CreateRecipeRequest(null, "x", null, simpleSpec())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        assertThatThrownBy(() -> off.saveFromRun(fromRun("x", Map.of(), false)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> off.delete(UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);

        verifyNoInteractions(untouched, agents, journals);
    }
}
