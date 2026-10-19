package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.tools.browseruse.BrowserRecipeSpec.Expect;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipeSpec.ParamSpec;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipeSpec.RecipeStep;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The gate every recipe passes: shape, domains, placeholders, and the no-literal-credential rule.
 */
class BrowserRecipeValidatorTest {

    private static RecipeStep navigate(String url) {
        return new RecipeStep("navigate", url, null, null, null, null, null, false);
    }

    private static RecipeStep click(String selector) {
        return new RecipeStep("click", null, selector, null, null, null, null, false);
    }

    private static RecipeStep type(String selector, String text) {
        return new RecipeStep("type", null, selector, text, null, null, null, false);
    }

    private static BrowserRecipeSpec spec(List<ParamSpec> params, RecipeStep... steps) {
        return new BrowserRecipeSpec(1, List.of("shop.example.com"), params, List.of(steps));
    }

    private static BrowserRecipeSpec spec(RecipeStep... steps) {
        return spec(List.of(), steps);
    }

    private static void rejects(BrowserRecipeSpec spec, String messagePart) {
        assertThatThrownBy(() -> BrowserRecipeValidator.validate(spec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(messagePart);
    }

    @Test
    void acceptsAWellFormedRecipeAndNormalizesIt() {
        BrowserRecipeSpec valid =
                BrowserRecipeValidator.validate(
                        new BrowserRecipeSpec(
                                1,
                                List.of("Shop.Example.com", "shop.example.com"),
                                List.of(new ParamSpec("query", true)),
                                List.of(
                                        new RecipeStep(
                                                "NAVIGATE",
                                                "https://shop.example.com/",
                                                null,
                                                null,
                                                null,
                                                new Expect(
                                                        "shop.example.com", null, null, null, null),
                                                5_000,
                                                false),
                                        type("#search", "{{query}}"),
                                        new RecipeStep(
                                                "wait_for",
                                                null,
                                                null,
                                                null,
                                                null,
                                                new Expect(null, null, null, "results", null),
                                                null,
                                                false))));

        assertThat(valid.allowedDomains()).containsExactly("shop.example.com");
        assertThat(valid.steps())
                .extracting(RecipeStep::action)
                .containsExactly("navigate", "type", "wait_for");
    }

    @Test
    void shapeRules() {
        rejects(spec(click("Orders")), "must start with a navigate");
        rejects(
                new BrowserRecipeSpec(1, List.of("shop.example.com"), List.of(), List.of()),
                "at least one step");
        rejects(
                new BrowserRecipeSpec(
                        2,
                        List.of("shop.example.com"),
                        List.of(),
                        List.of(navigate("https://shop.example.com/"))),
                "version");
        rejects(
                spec(
                        navigate("https://shop.example.com/"),
                        new RecipeStep("teleport", null, "x", null, null, null, null, false)),
                "unknown action");
        rejects(spec(navigate("https://shop.example.com/"), click(" ")), "selector is required");
        rejects(
                spec(
                        navigate("https://shop.example.com/"),
                        new RecipeStep("type", null, "#q", null, null, null, null, false)),
                "needs text");
        rejects(
                spec(
                        navigate("https://shop.example.com/"),
                        new RecipeStep("select", null, "#q", null, null, null, null, false)),
                "value is required");
        rejects(
                spec(
                        navigate("https://shop.example.com/"),
                        new RecipeStep("wait_for", null, null, null, null, null, null, false)),
                "expect condition");
        rejects(
                spec(
                        navigate("https://shop.example.com/"),
                        new RecipeStep("click", null, "x", null, null, null, 5, false)),
                "timeoutMs");
        List<RecipeStep> tooMany = new ArrayList<>();
        tooMany.add(navigate("https://shop.example.com/"));
        for (int i = 0; i < BrowserRecipeValidator.MAX_STEPS; i++) {
            tooMany.add(click("Orders"));
        }
        rejects(
                new BrowserRecipeSpec(1, List.of("shop.example.com"), List.of(), tooMany),
                "at most");
    }

    @Test
    void navigateMustStayInsideTheAllowedDomainsWithALiteralHost() {
        rejects(spec(navigate("https://evil.test/")), "outside the recipe's allowed domains");
        rejects(spec(navigate("https://shop.example.com.evil.test/")), "outside");
        rejects(
                spec(List.of(new ParamSpec("host", true)), navigate("https://{{host}}/x")),
                "literal");
        rejects(spec(navigate("https://user:pw@shop.example.com/")), "literal");
        rejects(spec(navigate("ftp://shop.example.com/")), "http");
        rejects(spec(navigate("javascript:alert(1)")), "http");
        // a subdomain of an allowed domain is fine, and so is a port
        BrowserRecipeValidator.validate(spec(navigate("https://www.shop.example.com:8443/x")));
    }

    @Test
    void placeholdersMustBeWellFormedAndDeclared() {
        rejects(spec(navigate("https://shop.example.com/?q={{query}}")), "undeclared parameter");
        rejects(
                spec(
                        List.of(new ParamSpec("query", true)),
                        navigate("https://shop.example.com/?q={{query"),
                        click("x")),
                "malformed");
        rejects(
                spec(
                        List.of(new ParamSpec("query", true)),
                        navigate("https://shop.example.com/"),
                        click("{{ bad name }}")),
                "malformed");
        BrowserRecipeValidator.validate(
                spec(
                        List.of(new ParamSpec("query", true)),
                        navigate("https://shop.example.com/?q={{query}}"),
                        click("{{query}}")));
    }

    @Test
    void parameterNamesAreChecked() {
        rejects(
                spec(List.of(new ParamSpec("secret", true)), navigate("https://shop.example.com/")),
                "reserved");
        rejects(
                spec(List.of(new ParamSpec("totp", true)), navigate("https://shop.example.com/")),
                "reserved");
        rejects(
                spec(List.of(new ParamSpec("1abc", true)), navigate("https://shop.example.com/")),
                "parameter names");
        rejects(
                spec(
                        List.of(new ParamSpec("a", true), new ParamSpec("A", false)),
                        navigate("https://shop.example.com/")),
                "twice");
    }

    @Test
    void aCredentialFieldCanOnlyReceiveAVaultPlaceholder() {
        RecipeStep nav = navigate("https://shop.example.com/login");

        rejects(spec(nav, type("input[type=password]", "hunter2")), "never a literal");
        rejects(spec(nav, type("#otp", "123456")), "never a literal");
        rejects(spec(nav, type("#password", "x{{secret.SHOP_PASSWORD}}")), "never a literal");
        rejects(
                spec(List.of(new ParamSpec("pw", true)), nav, type("#password", "{{pw}}")),
                "never a literal");
        BrowserRecipeValidator.validate(
                spec(nav, type("input[type=password]", "{{secret.SHOP_PASSWORD}}")));
        BrowserRecipeValidator.validate(spec(nav, type("#otp", "{{totp.SHOP_TOTP}}")));
        // clearing a field is not a credential
        BrowserRecipeValidator.validate(spec(nav, type("#password", "")));
        // an ordinary field may carry a literal
        BrowserRecipeValidator.validate(spec(nav, type("#nickname", "plain text")));
    }

    @Test
    void secretAndTotpPlaceholdersAreOnlyAllowedAsTypedText() {
        RecipeStep nav = navigate("https://shop.example.com/");

        rejects(spec(navigate("https://shop.example.com/?k={{secret.API_KEY}}")), "secret or TOTP");
        rejects(spec(nav, click("{{secret.API_KEY}}")), "secret or TOTP");
        rejects(
                spec(
                        nav,
                        new RecipeStep(
                                "select", null, "#x", null, "{{totp.X}}", null, null, false)),
                "secret or TOTP");
    }

    @Test
    void recognizesCredentialFieldsWithoutFlaggingOrdinaryOnes() {
        for (String sensitive :
                new String[] {
                    "input[type=password]",
                    "#otp",
                    "#user_password",
                    "Password",
                    "the verification code field",
                    "#pin",
                    "input[name=api_key]",
                    "#token",
                    "Card number",
                    "#mfa-input",
                    "#2fa-code",
                    "cvv",
                    "input.passcode"
                }) {
            assertThat(BrowserRecipeValidator.isSensitiveTarget(sensitive)).as(sensitive).isTrue();
        }
        for (String ordinary :
                new String[] {
                    "#email",
                    "search",
                    "#footprint",
                    "the passenger count",
                    "Compass heading",
                    "#spinner",
                    "#note",
                    "input[name=qty]",
                    "#pinned-items-list-of-things"
                }) {
            assertThat(BrowserRecipeValidator.isSensitiveTarget(ordinary)).as(ordinary).isFalse();
        }
    }

    @Test
    void recognizesClicksThatCommitSomething() {
        for (String irreversible :
                new String[] {
                    "Submit",
                    "button[type=submit]",
                    "Place order",
                    "Delete account",
                    "Sign out",
                    "Pay now",
                    "Confirm purchase",
                    "Send message"
                }) {
            assertThat(BrowserRecipeValidator.looksIrreversible(irreversible))
                    .as(irreversible)
                    .isTrue();
        }
        for (String harmless :
                new String[] {"Orders", "Add to cart", "Next", "Search", "#menu", "Sign in"}) {
            assertThat(BrowserRecipeValidator.looksIrreversible(harmless)).as(harmless).isFalse();
        }
    }
}
