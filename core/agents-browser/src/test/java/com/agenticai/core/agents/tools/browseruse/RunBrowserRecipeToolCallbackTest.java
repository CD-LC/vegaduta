package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipeSpec.Expect;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipeSpec.ParamSpec;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipeSpec.RecipeStep;
import com.agenticai.core.secrets.SecretVaultService;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@code run_browser_recipe} end to end over a real {@link BrowserRecipeService} (mock repository)
 * and a fake page: listing, a successful run, the stop-and-report failure contract, the
 * confirmation pause, run bookkeeping, and flag-off identity.
 */
class RunBrowserRecipeToolCallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID AGENT = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String PASSWORD = "S3cretPw!";

    /** Minimal scripted page: navigates, types, and clicks "Sign in" into a dashboard. */
    private static final class Page implements BrowserPageOps {
        final List<String> log = new ArrayList<>();
        final List<String> typed = new ArrayList<>();
        String url = "about:blank";
        boolean signInWorks = true;

        @Override
        public String navigate(String target) {
            log.add("navigate " + target);
            url = target;
            return url;
        }

        @Override
        public void click(String selector) {
            log.add("click " + selector);
            if ("Sign in".equals(selector) && signInWorks) {
                url = "https://shop.example.com/dashboard";
            }
        }

        @Override
        public void type(String selector, String text) {
            log.add("type " + selector);
            typed.add(text);
        }

        @Override
        public void select(String selector, String value) {
            log.add("select " + selector);
        }

        @Override
        public String currentUrl() {
            return url;
        }

        @Override
        public String pageText() {
            return "";
        }

        @Override
        public boolean waitForElement(String selector, int timeoutMs) {
            return false;
        }

        @Override
        public void settle(int timeoutMs) {}
    }

    private final AtomicLong clock = new AtomicLong(0);
    private final Page page = new Page();
    private BrowserRecipeRepository repository;
    private BrowserRecipe recipe;
    private BrowserRecipeService service;
    private RunBrowserRecipeToolCallback tool;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(BrowserRecipeRepository.class);
        service = newService(true);
        recipe = storedRecipe(loginSpec(), "Sign in to shop");
        when(repository.findByAgentIdOrAgentIdIsNullOrderByNameAsc(AGENT))
                .thenReturn(List.of(recipe));
        when(repository.findById(recipe.getId())).thenReturn(Optional.of(recipe));
        tool = toolOver(service);
        TenantContext.set(TenantContext.of("tenant-1", "user-1", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private BrowserRecipeService newService(boolean enabled) {
        ObjectProvider<AgentService> agents = mock(ObjectProvider.class);
        ObjectProvider<ToolCallLogRepository> journal = mock(ObjectProvider.class);
        return new BrowserRecipeService(
                repository,
                agents,
                journal,
                enabled,
                50,
                15_000,
                120,
                "",
                Clock.fixed(Instant.parse("2026-10-04T10:00:00Z"), ZoneOffset.UTC));
    }

    private RunBrowserRecipeToolCallback toolOver(BrowserRecipeService recipes) {
        BrowserRecipeReplayer.SecretValues vault =
                new BrowserRecipeReplayer.SecretValues() {
                    @Override
                    public Optional<String> secret(String name) {
                        return "SHOP_PASSWORD".equals(name)
                                ? Optional.of(PASSWORD)
                                : Optional.empty();
                    }

                    @Override
                    public Optional<String> totp(String name) {
                        return Optional.empty();
                    }
                };
        return new RunBrowserRecipeToolCallback(
                recipes,
                AGENT,
                tenantId -> page,
                subject -> vault,
                new BrowserRecipeReplayer(clock::get, clock::addAndGet));
    }

    private static BrowserRecipeSpec loginSpec() {
        return new BrowserRecipeSpec(
                1,
                List.of("shop.example.com"),
                List.of(new ParamSpec("email", true)),
                List.of(
                        new RecipeStep(
                                "navigate",
                                "https://shop.example.com/login",
                                null,
                                null,
                                null,
                                new Expect("shop.example.com", "/login", null, null, null),
                                null,
                                false),
                        new RecipeStep(
                                "type", null, "#email", "{{email}}", null, null, null, false),
                        new RecipeStep(
                                "type",
                                null,
                                "input[type=password]",
                                "{{secret.SHOP_PASSWORD}}",
                                null,
                                null,
                                null,
                                false),
                        new RecipeStep(
                                "click",
                                null,
                                "Sign in",
                                null,
                                null,
                                new Expect(null, null, "/dashboard", null, null),
                                null,
                                false),
                        new RecipeStep(
                                "click", null, "Place order", null, null, null, null, true)));
    }

    private static BrowserRecipe storedRecipe(BrowserRecipeSpec spec, String name)
            throws Exception {
        return new BrowserRecipe(
                AGENT,
                name,
                "Logs in",
                spec.allowedDomains(),
                MAPPER.writeValueAsString(spec),
                spec.steps().size(),
                "user-1",
                Instant.parse("2026-10-01T00:00:00Z"));
    }

    // ---- listing and lookup
    // ----------------------------------------------------------------------

    @Test
    void needsATenantContext() {
        TenantContext.clear();

        assertThat(tool.call("{}")).contains("error").contains("tenant");
    }

    @Test
    void listsTheRecipesTheAgentMayRunWhenNoRecipeIsNamed() {
        String listed = tool.call("{}");

        assertThat(listed)
                .contains(recipe.getId().toString())
                .contains("Sign in to shop")
                .contains("email")
                .contains("\"steps\":5");
        assertThat(tool.call("{\"recipeId\": \"list\"}")).isEqualTo(listed);
    }

    @Test
    void anUnknownRecipeIsAnErrorThatPointsAtTheList() {
        String result = tool.call("{\"recipeId\": \"nope\"}");

        assertThat(result).contains("error").contains("nope").contains("list");
        assertThat(page.log).isEmpty();
    }

    @Test
    void aRecipeCanBeRunByIdOrByName() {
        assertThat(
                        tool.call(
                                "{\"recipeId\": \""
                                        + recipe.getId()
                                        + "\", \"params\": {\"email\": \"a@b.co\"}, \"fromStep\": 1}"))
                .contains("\"status\":\"needs_confirmation\"");
        assertThat(
                        tool.call(
                                "{\"recipeId\": \"sign in TO shop\", \"params\": {\"email\": \"a@b.co\"}}"))
                .contains("needs_confirmation");
    }

    // ---- running
    // ---------------------------------------------------------------------------------

    @Test
    void runsUpToTheIrreversibleStepThenPausesForConfirmation() {
        String result =
                tool.call(
                        "{\"recipeId\": \"Sign in to shop\", \"params\": {\"email\": \"alice@example.com\"}}");

        assertThat(result)
                .contains("\"status\":\"needs_confirmation\"")
                .contains("\"failedStep\":5")
                .contains("\"stepsCompleted\":4")
                .contains("confirmIrreversible=true");
        assertThat(page.typed).containsExactly("alice@example.com", PASSWORD);
        // the pause is not a failure and is not counted as a run
        assertThat(recipe.getRunCount()).isZero();
    }

    @Test
    void confirmingResumesAndFinishesAndTheRunIsRecorded() {
        String result =
                tool.call(
                        "{\"recipeId\": \"Sign in to shop\", \"params\": {\"email\": \"alice@example.com\"},"
                                + " \"confirmIrreversible\": true}");

        assertThat(result)
                .contains("\"status\":\"success\"")
                .contains("\"stepsCompleted\":5")
                .contains("shop.example.com/dashboard");
        assertThat(page.log).endsWith("click Place order");
        assertThat(recipe.getRunCount()).isEqualTo(1);
        assertThat(recipe.getFailCount()).isZero();
        assertThat(recipe.getLastFailure()).isNull();
        assertThat(result).doesNotContain(PASSWORD).doesNotContain("alice@example.com");
    }

    @Test
    void aFailedVerificationIsReportedAndRecordedAndTheModelIsToldToContinueByHand() {
        page.signInWorks = false;

        String result =
                tool.call(
                        "{\"recipeId\": \"Sign in to shop\", \"params\": {\"email\": \"alice@example.com\"},"
                                + " \"confirmIrreversible\": true}");

        assertThat(result)
                .contains("\"status\":\"failed\"")
                .contains("\"failedStep\":4")
                .contains("\"failedAction\":\"click\"")
                .contains("\"failedTarget\":\"Sign in\"")
                .contains("/dashboard")
                .contains("browser_read_page_text");
        assertThat(page.log).doesNotContain("click Place order");
        assertThat(recipe.getRunCount()).isEqualTo(1);
        assertThat(recipe.getFailCount()).isEqualTo(1);
        assertThat(recipe.getLastFailure()).contains("step 4");
        assertThat(result).doesNotContain(PASSWORD);
    }

    @Test
    void aMissingParameterIsReportedWithoutTouchingTheBrowser() {
        String result = tool.call("{\"recipeId\": \"Sign in to shop\"}");

        assertThat(result)
                .contains("\"status\":\"failed\"")
                .contains("missing required parameter 'email'");
        assertThat(page.log).isEmpty();
    }

    @Test
    void aStoredRecipeThatNoLongerPassesValidationIsRefusedNotReplayed() throws Exception {
        BrowserRecipeSpec tampered =
                new BrowserRecipeSpec(
                        1,
                        List.of("shop.example.com"),
                        List.of(),
                        List.of(
                                new RecipeStep(
                                        "navigate",
                                        "https://shop.example.com/login",
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
        BrowserRecipe bad = storedRecipe(tampered, "Tampered");
        when(repository.findByAgentIdOrAgentIdIsNullOrderByNameAsc(AGENT)).thenReturn(List.of(bad));

        String result = tool.call("{\"recipeId\": \"Tampered\"}");

        assertThat(result).contains("error").contains("cannot be run");
        assertThat(page.log).isEmpty();
    }

    // ---- vault adapter
    // ---------------------------------------------------------------------------

    @Test
    void theVaultAdapterOnlyReachesSecretsNamedForBrowserUse() {
        SecretVaultService vault = mock(SecretVaultService.class);
        when(vault.resolveForRun("BROWSER_PW", "user-1")).thenReturn(Optional.of("pw-value"));
        when(vault.resolveForRun("BROWSER_2FA", "user-1"))
                .thenReturn(Optional.of("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"));
        RunBrowserRecipeToolCallback.VaultSecretValues values =
                new RunBrowserRecipeToolCallback.VaultSecretValues(
                        vault, "user-1", () -> 59L, "BROWSER_");

        assertThat(values.secret("OPENAI_KEY")).isEmpty();
        assertThat(values.totp("OTHER_SEED")).isEmpty();
        verify(vault, never()).resolveForRun(eq("OPENAI_KEY"), anyString());
        verify(vault, never()).resolveForRun(eq("OTHER_SEED"), anyString());
        assertThat(values.secret("BROWSER_PW")).contains("pw-value");
        assertThat(values.totp("BROWSER_2FA")).contains("287082");
    }

    // ---- flag-off identity
    // -----------------------------------------------------------------------

    @Test
    void withTheFlagOffNoRecipeIsVisibleOrRunnable() {
        RunBrowserRecipeToolCallback off = toolOver(newService(false));

        assertThat(off.call("{\"recipeId\": \"Sign in to shop\"}")).contains("error");
        assertThat(off.call("{}")).contains("\"recipes\":[]");
        assertThat(page.log).isEmpty();
    }

    @Test
    void theDefinitionIsShortAndNamed() {
        assertThat(tool.getToolDefinition().name()).isEqualTo("run_browser_recipe");
        assertThat(tool.getToolDefinition().description().length()).isLessThan(600);
    }
}
