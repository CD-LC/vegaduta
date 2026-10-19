package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.tools.browseruse.BrowserRecipeReplayer.Options;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipeReplayer.Outcome;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipeReplayer.SecretValues;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipeReplayer.Status;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipeSpec.Expect;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipeSpec.ParamSpec;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipeSpec.RecipeStep;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Deterministic replay against a fake page: the happy path, and every way a replay must STOP -
 * failed verification, a failed action, a missing parameter or secret, leaving the allowed domains,
 * an irreversible step, a spent time budget - plus the guarantee that a resolved secret never
 * appears in the outcome and that nothing after a failed step runs. A fake clock makes the polling
 * paths instant.
 */
class BrowserRecipeReplayerTest {

    private static final String PASSWORD = "S3cretPw!";
    private static final String TOTP_CODE = "654321";

    /**
     * A scripted page: records every action and lets the test decide what each one does to the
     * page.
     */
    private static final class FakePage implements BrowserPageOps {
        final List<String> log = new ArrayList<>();
        final List<String> typed = new ArrayList<>();
        final Map<String, Consumer<FakePage>> clickEffects = new HashMap<>();
        final Set<String> brokenSelectors = new HashSet<>();
        final Set<String> presentSelectors = new HashSet<>();
        final Map<String, String> redirects = new HashMap<>();
        final AtomicLong clock;
        String url = "about:blank";
        String text = "";
        int pageTextCalls;
        int textAppearsAfterCalls = 0;
        String lateText;
        RuntimeException typeFailure;
        long navigateCostMs;

        FakePage(AtomicLong clock) {
            this.clock = clock;
        }

        @Override
        public String navigate(String target) {
            log.add("navigate " + target);
            clock.addAndGet(navigateCostMs);
            url = redirects.getOrDefault(target, target);
            return url;
        }

        @Override
        public void click(String selector) {
            log.add("click " + selector);
            if (brokenSelectors.contains(selector)) {
                throw new BrowserUseException(
                        "no element matches '" + selector + "'\nCall log: waiting for locator");
            }
            clickEffects.getOrDefault(selector, page -> {}).accept(this);
        }

        @Override
        public void type(String selector, String value) {
            log.add("type " + selector);
            if (typeFailure != null) {
                throw typeFailure;
            }
            typed.add(value);
        }

        @Override
        public void select(String selector, String value) {
            log.add("select " + selector + "=" + value);
        }

        @Override
        public String currentUrl() {
            return url;
        }

        @Override
        public String pageText() {
            pageTextCalls++;
            return lateText != null && pageTextCalls > textAppearsAfterCalls ? lateText : text;
        }

        @Override
        public boolean waitForElement(String selector, int timeoutMs) {
            return presentSelectors.contains(selector);
        }

        @Override
        public void settle(int timeoutMs) {
            // nothing to wait for in a fake
        }
    }

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final FakePage page = new FakePage(clock);
    private final BrowserRecipeReplayer replayer =
            new BrowserRecipeReplayer(clock::get, clock::addAndGet);

    private static final SecretValues VAULT =
            new SecretValues() {
                @Override
                public Optional<String> secret(String name) {
                    return "PW".equals(name) ? Optional.of(PASSWORD) : Optional.empty();
                }

                @Override
                public Optional<String> totp(String name) {
                    return "SHOP".equals(name) ? Optional.of(TOTP_CODE) : Optional.empty();
                }
            };

    private static RecipeStep navigate(String url, Expect expect) {
        return new RecipeStep("navigate", url, null, null, null, expect, null, false);
    }

    private static RecipeStep click(String selector, Expect expect) {
        return new RecipeStep("click", null, selector, null, null, expect, null, false);
    }

    private static RecipeStep type(String selector, String text) {
        return new RecipeStep("type", null, selector, text, null, null, null, false);
    }

    private static RecipeStep waitFor(Expect expect) {
        return new RecipeStep("wait_for", null, null, null, null, expect, null, false);
    }

    private static BrowserRecipeSpec spec(List<ParamSpec> params, RecipeStep... steps) {
        return new BrowserRecipeSpec(1, List.of("shop.example.com"), params, List.of(steps));
    }

    private Outcome run(BrowserRecipeSpec spec, Map<String, String> params) {
        return replayer.replay(spec, params, page, VAULT, Options.standard());
    }

    private static Expect urlContains(String s) {
        return new Expect(null, null, s, null, null);
    }

    private static Expect text(String s) {
        return new Expect(null, null, null, s, null);
    }

    private BrowserRecipeSpec loginRecipe() {
        return spec(
                List.of(new ParamSpec("email", true)),
                navigate(
                        "https://shop.example.com/login",
                        new Expect("shop.example.com", "/login", null, null, null)),
                type("#email", "{{email}}"),
                type("input[type=password]", "{{secret.PW}}"),
                type("#otp", "{{totp.SHOP}}"),
                click("Sign in", urlContains("/dashboard")),
                waitFor(text("welcome")));
    }

    private void signInWorks() {
        page.clickEffects.put(
                "Sign in",
                p -> {
                    p.url = "https://shop.example.com/dashboard";
                    p.text = "Welcome back, Alice";
                });
    }

    // ---- success
    // ---------------------------------------------------------------------------------

    @Test
    void replaysEveryStepAndResolvesParametersSecretsAndTotpServerSide() {
        signInWorks();

        Outcome outcome = run(loginRecipe(), Map.of("email", "alice@example.com"));

        assertThat(outcome.status()).isEqualTo(Status.SUCCESS);
        assertThat(outcome.stepsCompleted()).isEqualTo(6);
        assertThat(outcome.totalSteps()).isEqualTo(6);
        assertThat(outcome.currentUrl()).isEqualTo("https://shop.example.com/dashboard");
        assertThat(page.typed).containsExactly("alice@example.com", PASSWORD, TOTP_CODE);
        assertThat(page.log)
                .containsExactly(
                        "navigate https://shop.example.com/login",
                        "type #email",
                        "type input[type=password]",
                        "type #otp",
                        "click Sign in");
        // the outcome is safe to hand to the model: no value of any kind
        assertThat(outcome.toString())
                .doesNotContain(PASSWORD)
                .doesNotContain(TOTP_CODE)
                .doesNotContain("alice@example.com");
    }

    @Test
    void parametersInUrlsAreEncodedAndAParameterValueCannotInjectAPlaceholder() {
        BrowserRecipeSpec recipe =
                spec(
                        List.of(new ParamSpec("query", true)),
                        navigate("https://shop.example.com/search?q={{query}}", null),
                        type("#note", "{{query}}"));

        Outcome outcome = run(recipe, Map.of("query", "blue widget {{secret.PW}}"));

        assertThat(outcome.success()).isTrue();
        assertThat(page.log.get(0))
                .isEqualTo(
                        "navigate https://shop.example.com/search?q=blue%20widget%20%7B%7Bsecret.PW%7D%7D");
        // single-pass substitution: the typed text is the literal characters, not the vault value
        assertThat(page.typed).containsExactly("blue widget {{secret.PW}}");
        assertThat(page.typed.get(0)).doesNotContain(PASSWORD);
    }

    @Test
    void selectAndWaitForStepsRun() {
        page.presentSelectors.add("#results");
        BrowserRecipeSpec recipe =
                spec(
                        List.of(),
                        navigate("https://shop.example.com/", null),
                        new RecipeStep("select", null, "#sort", null, "price", null, null, false),
                        waitFor(new Expect(null, null, null, null, "#results")));

        Outcome outcome = run(recipe, Map.of());

        assertThat(outcome.success()).isTrue();
        assertThat(page.log).contains("select #sort=price");
    }

    // ---- stopping
    // --------------------------------------------------------------------------------

    @Test
    void stopsAtTheFirstFailedVerificationAndRunsNothingAfterIt() {
        // the click does nothing: the page never reaches /dashboard

        Outcome outcome = run(loginRecipe(), Map.of("email", "alice@example.com"));

        assertThat(outcome.status()).isEqualTo(Status.FAILED);
        assertThat(outcome.failedStep()).isEqualTo(5);
        assertThat(outcome.stepsCompleted()).isEqualTo(4);
        assertThat(outcome.action()).isEqualTo("click");
        assertThat(outcome.target()).isEqualTo("Sign in");
        assertThat(outcome.reason())
                .contains("verification failed after step 5")
                .contains("/dashboard");
        assertThat(outcome.currentUrl()).isEqualTo("https://shop.example.com/login");
        // the wait_for after it never ran, and the replay waited out the step budget rather than
        // failing instantly
        assertThat(page.pageTextCalls).isZero();
        assertThat(clock.get()).isGreaterThanOrEqualTo(1_000_000L + 15_000L);
    }

    @Test
    void aVerificationThatBecomesTrueWithinTheTimeoutPasses() {
        signInWorks();
        page.text = "";
        page.lateText = "Welcome back, Alice";
        page.textAppearsAfterCalls = 3;
        BrowserRecipeSpec recipe =
                spec(
                        List.of(),
                        navigate("https://shop.example.com/", null),
                        waitFor(text("welcome")));

        Outcome outcome = run(recipe, Map.of());

        assertThat(outcome.success()).isTrue();
        assertThat(page.pageTextCalls).isGreaterThan(3);
    }

    @Test
    void aStepTimeoutOfItsOwnIsHonoured() {
        BrowserRecipeSpec recipe =
                spec(
                        List.of(),
                        navigate("https://shop.example.com/", null),
                        new RecipeStep(
                                "wait_for",
                                null,
                                null,
                                null,
                                null,
                                text("never shown"),
                                600,
                                false));

        Outcome outcome = run(recipe, Map.of());

        assertThat(outcome.status()).isEqualTo(Status.FAILED);
        assertThat(outcome.reason()).contains("never shown");
        assertThat(clock.get() - 1_000_000L).isLessThan(2_000L);
    }

    @Test
    void verifiesHostAndPathAndSelector() {
        page.redirects.put(
                "https://shop.example.com/login", "https://shop.example.com/maintenance");
        Outcome hostPath =
                run(
                        spec(
                                List.of(),
                                navigate(
                                        "https://shop.example.com/login",
                                        new Expect(
                                                "shop.example.com", "/login", null, null, null))),
                        Map.of());
        assertThat(hostPath.status()).isEqualTo(Status.FAILED);
        assertThat(hostPath.reason()).contains("path to start with /login");

        Outcome missingElement =
                replayer.replay(
                        spec(
                                List.of(),
                                navigate("https://shop.example.com/", null),
                                waitFor(new Expect(null, null, null, null, "#results"))),
                        Map.of(),
                        new FakePage(clock),
                        VAULT,
                        Options.standard());
        assertThat(missingElement.status()).isEqualTo(Status.FAILED);
        assertThat(missingElement.reason()).contains("#results");
    }

    @Test
    void aFailedActionStopsTheReplayWithTheFirstLineOfTheError() {
        page.brokenSelectors.add("Sign in");

        Outcome outcome = run(loginRecipe(), Map.of("email", "alice@example.com"));

        assertThat(outcome.status()).isEqualTo(Status.FAILED);
        assertThat(outcome.failedStep()).isEqualTo(5);
        assertThat(outcome.reason()).isEqualTo("no element matches 'Sign in'");
        assertThat(page.log).endsWith("click Sign in");
    }

    @Test
    void missingRequiredParameterFailsBeforeAnythingRuns() {
        Outcome outcome = run(loginRecipe(), Map.of());

        assertThat(outcome.status()).isEqualTo(Status.FAILED);
        assertThat(outcome.failedStep()).isZero();
        assertThat(outcome.reason()).contains("missing required parameter 'email'");
        assertThat(page.log).isEmpty();

        Outcome blank = run(loginRecipe(), Map.of("email", "  "));
        assertThat(blank.reason()).contains("email");
        assertThat(page.log).isEmpty();
    }

    @Test
    void anUnavailableSecretStopsTheReplayAtThatStepWithoutRunningIt() {
        BrowserRecipeSpec recipe =
                spec(
                        List.of(),
                        navigate("https://shop.example.com/login", null),
                        type("#password", "{{secret.MISSING}}"),
                        click("Next", null));

        Outcome outcome = run(recipe, Map.of());

        assertThat(outcome.status()).isEqualTo(Status.FAILED);
        assertThat(outcome.failedStep()).isEqualTo(2);
        assertThat(outcome.reason()).contains("MISSING").contains("not available");
        assertThat(page.typed).isEmpty();
        assertThat(page.log).containsExactly("navigate https://shop.example.com/login");
    }

    @Test
    void aSecretValueIsScrubbedFromAnErrorTheBrowserEchoesBack() {
        page.typeFailure =
                new BrowserUseException(
                        "fill failed: element rejected value "
                                + PASSWORD
                                + " and then "
                                + TOTP_CODE);
        BrowserRecipeSpec recipe =
                spec(
                        List.of(),
                        navigate("https://shop.example.com/login", null),
                        type("#password", "{{secret.PW}}"));

        Outcome outcome = run(recipe, Map.of());

        assertThat(outcome.status()).isEqualTo(Status.FAILED);
        assertThat(outcome.failedStep()).isEqualTo(2);
        // the vault value that was just resolved is scrubbed wherever the browser's error echoes it
        assertThat(outcome.reason()).contains("[redacted]").doesNotContain(PASSWORD);
        assertThat(outcome.toString()).doesNotContain(PASSWORD);
        // the step's target is its selector, never the typed text
        assertThat(outcome.target()).isEqualTo("#password");
    }

    // ---- domains
    // ---------------------------------------------------------------------------------

    @Test
    void refusesToNavigateOutsideTheRecipesDomains() {
        BrowserRecipeSpec recipe =
                spec(
                        List.of(new ParamSpec("path", true)),
                        navigate("https://evil.test/{{path}}", null));

        Outcome outcome = run(recipe, Map.of("path", "x"));

        assertThat(outcome.status()).isEqualTo(Status.FAILED);
        assertThat(outcome.reason()).contains("outside the recipe's allowed domains");
        assertThat(page.log).isEmpty();
    }

    @Test
    void stopsWhenAStepLandsTheBrowserOnAnotherSite() {
        page.clickEffects.put(
                "Pay with partner", p -> p.url = "https://partner.example.net/checkout");
        BrowserRecipeSpec recipe =
                spec(
                        List.of(),
                        navigate("https://shop.example.com/cart", null),
                        click("Pay with partner", null),
                        click("Confirm", null));

        Outcome outcome = run(recipe, Map.of());

        assertThat(outcome.status()).isEqualTo(Status.FAILED);
        assertThat(outcome.failedStep()).isEqualTo(2);
        assertThat(outcome.reason())
                .contains("left the recipe's allowed domains")
                .contains("partner.example.net");
        assertThat(page.log).doesNotContain("click Confirm");
    }

    // ---- irreversible steps and budget
    // -----------------------------------------------------------

    private BrowserRecipeSpec checkoutRecipe() {
        return spec(
                List.of(),
                navigate("https://shop.example.com/cart", null),
                click("Review order", null),
                new RecipeStep("click", null, "Place order", null, null, null, null, true),
                click("Back to shop", null));
    }

    @Test
    void pausesBeforeAnIrreversibleStepUnlessConfirmed() {
        Outcome paused = run(checkoutRecipe(), Map.of());

        assertThat(paused.status()).isEqualTo(Status.NEEDS_CONFIRMATION);
        assertThat(paused.failedStep()).isEqualTo(3);
        assertThat(paused.stepsCompleted()).isEqualTo(2);
        assertThat(paused.reason()).contains("confirmIrreversible=true").contains("fromStep=3");
        assertThat(page.log).doesNotContain("click Place order");

        Outcome resumed =
                replayer.replay(
                        checkoutRecipe(),
                        Map.of(),
                        page,
                        VAULT,
                        new Options(true, 15_000, 120_000L, 3));
        assertThat(resumed.status()).isEqualTo(Status.SUCCESS);
        assertThat(page.log).endsWith("click Place order", "click Back to shop");
        // resuming does not redo the earlier steps
        assertThat(page.log.stream().filter(l -> l.startsWith("navigate")).count()).isEqualTo(1);
    }

    @Test
    void stopsWhenTheTimeBudgetIsSpent() {
        page.navigateCostMs = 5_000;

        Outcome outcome =
                replayer.replay(
                        checkoutRecipe(),
                        Map.of(),
                        page,
                        VAULT,
                        new Options(true, 15_000, 1_000L, 1));

        assertThat(outcome.status()).isEqualTo(Status.FAILED);
        assertThat(outcome.failedStep()).isEqualTo(2);
        assertThat(outcome.reason()).contains("time budget");
        assertThat(page.log).containsExactly("navigate https://shop.example.com/cart");
    }
}
