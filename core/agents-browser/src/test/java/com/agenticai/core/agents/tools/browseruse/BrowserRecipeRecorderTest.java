package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.tools.browseruse.BrowserRecipeRecorder.JournalCall;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipeSpec.RecipeStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Recipe extraction from a fake tool-call journal: what is kept, what becomes a placeholder, and -
 * above all - that no user-supplied value, credential or 2FA code can end up in the stored recipe.
 */
class BrowserRecipeRecorderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JournalCall nav(String url, String finalUrl) {
        return new JournalCall(
                "browser_navigate",
                "{\"url\":\"" + url + "\"}",
                "{\"url\":\"" + finalUrl + "\",\"title\":\"T\",\"note\":\"n\"}",
                null);
    }

    private static JournalCall click(String selector) {
        return new JournalCall(
                "browser_click",
                "{\"selector\":\"" + selector + "\"}",
                "{\"clicked\":\"" + selector + "\"}",
                null);
    }

    private static JournalCall type(String selector, String text) {
        return new JournalCall(
                "browser_type",
                "{\"selector\":\"" + selector + "\",\"text\":\"" + text + "\"}",
                "{\"typedInto\":\"" + selector + "\"}",
                null);
    }

    private static JournalCall totp(String secretName, String code) {
        return new JournalCall(
                "browser_totp",
                "{\"secretName\":\"" + secretName + "\"}",
                "{\"code\":\"" + code + "\",\"validForSeconds\":12,\"digits\":6}",
                null);
    }

    private static Map<String, String> params(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static List<JournalCall> loginAndSearchRun() {
        return List.of(
                nav("https://shop.example.com/login", "https://shop.example.com/login"),
                new JournalCall("browser_read_page_text", "{}", "{\"text\":\"Sign in\"}", null),
                type("#email", "alice@example.com"),
                type("input[type=password]", "{{secret.SHOP_PASSWORD}}"),
                click("Sign in"),
                totp("shop_totp", "482913"),
                type("#otp", "482913"),
                click("Verify"),
                nav(
                        "https://shop.example.com/search?q=blue%20widget",
                        "https://shop.example.com/search?q=blue%20widget"),
                type("input[name=note]", "blue widget"),
                click("Place order"));
    }

    @Test
    void recordsOnlySuccessfulBrowserActionsInOrder() throws Exception {
        BrowserRecipeRecorder.Recorded recorded =
                BrowserRecipeRecorder.record(
                        loginAndSearchRun(),
                        params("email", "alice@example.com", "query", "blue widget"),
                        false);

        List<RecipeStep> steps = recorded.spec().steps();
        assertThat(steps)
                .extracting(RecipeStep::action)
                .containsExactly(
                        "navigate",
                        "type",
                        "type",
                        "click",
                        "type",
                        "click",
                        "navigate",
                        "type",
                        "click");
        assertThat(recorded.spec().allowedDomains()).containsExactly("shop.example.com");
        assertThat(recorded.spec().version()).isEqualTo(BrowserRecipeSpec.CURRENT_VERSION);
    }

    @Test
    void userSuppliedValuesBecomeParametersAndAreNeverStored() throws Exception {
        BrowserRecipeRecorder.Recorded recorded =
                BrowserRecipeRecorder.record(
                        loginAndSearchRun(),
                        params("email", "alice@example.com", "query", "blue widget"),
                        false);

        List<RecipeStep> steps = recorded.spec().steps();
        assertThat(steps.get(1).text()).isEqualTo("{{email}}");
        assertThat(steps.get(6).url()).isEqualTo("https://shop.example.com/search?q={{query}}");
        // the free-text note field held the same value
        assertThat(steps.get(7).text()).isEqualTo("{{query}}");
        assertThat(recorded.spec().params())
                .extracting(BrowserRecipeSpec.ParamSpec::name)
                .containsExactlyInAnyOrder("email", "query");
        String json = MAPPER.writeValueAsString(recorded.spec());
        assertThat(json)
                .doesNotContain("alice@example.com")
                .doesNotContain("blue widget")
                .doesNotContain("blue%20widget");
    }

    @Test
    void secretPlaceholdersSurviveAndATotpCodeIsRewrittenToItsPlaceholder() throws Exception {
        BrowserRecipeRecorder.Recorded recorded =
                BrowserRecipeRecorder.record(
                        loginAndSearchRun(),
                        params("email", "alice@example.com", "query", "blue widget"),
                        false);

        List<RecipeStep> steps = recorded.spec().steps();
        assertThat(steps.get(2).text()).isEqualTo("{{secret.SHOP_PASSWORD}}");
        assertThat(steps.get(4).text()).isEqualTo("{{totp.SHOP_TOTP}}");
        assertThat(MAPPER.writeValueAsString(recorded.spec())).doesNotContain("482913");
    }

    @Test
    void navigateVerificationIsDerivedFromTheJournal() {
        BrowserRecipeRecorder.Recorded recorded =
                BrowserRecipeRecorder.record(
                        List.of(
                                nav(
                                        "https://shop.example.com/account",
                                        "https://shop.example.com/account"),
                                click("Orders")),
                        Map.of(),
                        false);

        BrowserRecipeSpec.Expect expect = recorded.spec().steps().get(0).expect();
        assertThat(expect.urlHost()).isEqualTo("shop.example.com");
        assertThat(expect.urlPathPrefix()).isEqualTo("/account");
    }

    @Test
    void aRedirectedNavigateOnlyPinsTheHostNotThePath() {
        BrowserRecipeRecorder.Recorded recorded =
                BrowserRecipeRecorder.record(
                        List.of(
                                nav(
                                        "https://shop.example.com/account",
                                        "https://accounts.example.com/login?next=1"),
                                click("Continue")),
                        Map.of(),
                        false);

        BrowserRecipeSpec.Expect expect = recorded.spec().steps().get(0).expect();
        assertThat(expect.urlHost()).isEqualTo("accounts.example.com");
        assertThat(expect.urlPathPrefix()).isNull();
        // both hosts are in the recipe's scope, or the replay could never follow its own redirect
        assertThat(recorded.spec().allowedDomains())
                .containsExactly("shop.example.com", "accounts.example.com");
    }

    @Test
    void irreversibleLookingClicksAreFlagged() {
        BrowserRecipeRecorder.Recorded recorded =
                BrowserRecipeRecorder.record(
                        loginAndSearchRun(),
                        params("email", "alice@example.com", "query", "blue widget"),
                        false);

        List<RecipeStep> steps = recorded.spec().steps();
        assertThat(steps.get(8).irreversible()).isTrue();
        assertThat(steps.get(3).irreversible()).isFalse();
        assertThat(recorded.warnings()).anyMatch(w -> w.contains("submit or commit"));
    }

    @Test
    void failedAttemptsAndNonActionToolsAreDropped() {
        List<JournalCall> run =
                List.of(
                        nav("https://shop.example.com/", "https://shop.example.com/"),
                        new JournalCall(
                                "browser_click",
                                "{\"selector\":\"#nope\"}",
                                "{\"error\":\"no matching element\"}",
                                null),
                        new JournalCall(
                                "browser_click",
                                "{\"selector\":\"#boom\"}",
                                null,
                                "TimeoutException"),
                        new JournalCall(
                                "browser_click",
                                "{\"selector\":\"#gated\"}",
                                "This tool call (browser_click) is waiting for approval",
                                null),
                        new JournalCall("browser_screenshot", "{}", "{\"image\":\"...\"}", null),
                        new JournalCall(
                                "fetch_web_page",
                                "{\"url\":\"https://x.example.com\"}",
                                "{}",
                                null),
                        click("Orders"));

        BrowserRecipeRecorder.Recorded recorded =
                BrowserRecipeRecorder.record(run, Map.of(), false);

        assertThat(recorded.spec().steps())
                .extracting(RecipeStep::action)
                .containsExactly("navigate", "click");
        assertThat(recorded.spec().steps().get(1).selector()).isEqualTo("Orders");
    }

    @Test
    void aLiteralInACredentialFieldAbortsTheRecordingWithoutEchoingIt() {
        List<JournalCall> run =
                List.of(
                        nav("https://shop.example.com/login", "https://shop.example.com/login"),
                        type("input[type=password]", "hunter2-hunter2"));

        assertThatThrownBy(() -> BrowserRecipeRecorder.record(run, Map.of(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential")
                .hasMessageContaining("{{secret.NAME}}")
                .hasMessageNotContaining("hunter2");
    }

    @Test
    void anUnlabelledLiteralIsRefusedUnlessLiteralTextIsAllowedExplicitly() {
        List<JournalCall> run =
                List.of(
                        nav("https://shop.example.com/", "https://shop.example.com/"),
                        type("#field-17", "a fixed phrase"));

        assertThatThrownBy(() -> BrowserRecipeRecorder.record(run, Map.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("step 2")
                .hasMessageNotContaining("a fixed phrase");

        BrowserRecipeRecorder.Recorded allowed = BrowserRecipeRecorder.record(run, Map.of(), true);
        assertThat(allowed.spec().steps().get(1).text()).isEqualTo("a fixed phrase");
        assertThat(allowed.warnings()).anyMatch(w -> w.contains("literal"));
    }

    @Test
    void parameterEdgeCases() {
        List<JournalCall> run =
                List.of(
                        nav("https://shop.example.com/", "https://shop.example.com/"),
                        type("#q", "{{secret.TOKEN_A}}"));

        BrowserRecipeRecorder.Recorded recorded =
                BrowserRecipeRecorder.record(
                        run,
                        params("unused", "never-typed-anywhere", "short", "ab", "blank", " "),
                        false);

        assertThat(recorded.warnings())
                .anyMatch(w -> w.contains("'unused'") && w.contains("not found"));
        assertThat(recorded.warnings())
                .anyMatch(w -> w.contains("'short'") && w.contains("too short"));
        assertThat(recorded.warnings()).anyMatch(w -> w.contains("'blank'"));
        assertThat(recorded.spec().params()).isEmpty();

        assertThatThrownBy(
                        () ->
                                BrowserRecipeRecorder.record(
                                        run, params("secret", "whatever-value"), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                BrowserRecipeRecorder.record(
                                        run, params("1bad", "whatever-value"), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aParameterValueNeverRewritesTheHost() {
        BrowserRecipeRecorder.Recorded recorded =
                BrowserRecipeRecorder.record(
                        List.of(
                                nav(
                                        "https://shop.example.com/example",
                                        "https://shop.example.com/example"),
                                click("Orders")),
                        params("word", "example"),
                        false);

        RecipeStep first = recorded.spec().steps().get(0);
        assertThat(first.url()).isEqualTo("https://shop.example.com/{{word}}");
        assertThat(recorded.spec().allowedDomains()).containsExactly("shop.example.com");
    }

    @Test
    void refusesARunThatIsNotReplayableFromAColdStart() {
        assertThatThrownBy(
                        () ->
                                BrowserRecipeRecorder.record(
                                        List.of(click("Orders")), Map.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("navigate");
        assertThatThrownBy(() -> BrowserRecipeRecorder.record(List.of(), Map.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nothing to record");
        assertThatThrownBy(
                        () ->
                                BrowserRecipeRecorder.record(
                                        List.of(
                                                new JournalCall(
                                                        "browser_screenshot", "{}", "{}", null)),
                                        Map.of(),
                                        false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
