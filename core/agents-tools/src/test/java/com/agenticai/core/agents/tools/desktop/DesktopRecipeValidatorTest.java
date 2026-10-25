package com.agenticai.core.agents.tools.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.tools.desktop.DesktopRecipeSpec.Expect;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeSpec.ParamSpec;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeSpec.RecipeStep;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The gate every desktop recipe passes: first step establishes an app, app names are literal and
 * inside {@code allowedApps}, a {@code {{secret.NAME}}} lives only inside a type step's text, and
 * each action carries the fields it needs.
 */
class DesktopRecipeValidatorTest {

    private static RecipeStep step(
            String action,
            String app,
            String label,
            String key,
            String target,
            String text,
            Integer waitMs) {
        return new RecipeStep(
                action, app, label, null, null, null, text, key, target, waitMs, null, null, false);
    }

    private static RecipeStep activate(String app) {
        return step(DesktopRecipeSpec.ACTIVATE, app, null, null, null, null, null);
    }

    private static DesktopRecipeSpec spec(List<String> apps, List<RecipeStep> steps) {
        return new DesktopRecipeSpec(DesktopRecipeSpec.CURRENT_VERSION, apps, List.of(), steps);
    }

    @Test
    void aValidSpecIsNormalized() {
        DesktopRecipeSpec out =
                DesktopRecipeValidator.validate(
                        spec(
                                List.of("Safari", "Safari", " "),
                                List.of(
                                        activate("Safari"),
                                        step(
                                                DesktopRecipeSpec.PRESS,
                                                "Safari",
                                                "OK",
                                                null,
                                                null,
                                                null,
                                                null))));
        assertThat(out.allowedApps()).containsExactly("safari");
        assertThat(out.steps()).hasSize(2);
    }

    @Test
    void theFirstStepMustEstablishAnApp() {
        assertThatThrownBy(
                        () ->
                                DesktopRecipeValidator.validate(
                                        spec(
                                                List.of("safari"),
                                                List.of(
                                                        step(
                                                                DesktopRecipeSpec.PRESS,
                                                                "safari",
                                                                "OK",
                                                                null,
                                                                null,
                                                                null,
                                                                null)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("step 1");

        // open or activate - either establishes the world the rest of the recipe acts in.
        DesktopRecipeValidator.validate(
                spec(
                        List.of(),
                        List.of(
                                step(
                                        DesktopRecipeSpec.OPEN,
                                        null,
                                        null,
                                        null,
                                        "https://x.example",
                                        null,
                                        null))));
    }

    @Test
    void anAppMustBeInsideAllowedApps() {
        assertThatThrownBy(
                        () ->
                                DesktopRecipeValidator.validate(
                                        spec(List.of("safari"), List.of(activate("Terminal")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the recipe's allowed apps");
    }

    @Test
    void anAppCannotBeAPlaceholder() {
        DesktopRecipeSpec withParam =
                new DesktopRecipeSpec(
                        DesktopRecipeSpec.CURRENT_VERSION,
                        List.of("safari"),
                        List.of(new ParamSpec("app_name", true)),
                        List.of(activate("{{app_name}}")));
        assertThatThrownBy(() -> DesktopRecipeValidator.validate(withParam))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("literal");
    }

    @Test
    void aSecretLivesOnlyInsideTypeText() {
        DesktopRecipeSpec good =
                new DesktopRecipeSpec(
                        DesktopRecipeSpec.CURRENT_VERSION,
                        List.of("notes"),
                        List.of(),
                        List.of(
                                activate("Notes"),
                                step(
                                        DesktopRecipeSpec.TYPE,
                                        null,
                                        null,
                                        null,
                                        null,
                                        "{{secret.DESKTOP_PW}}",
                                        null)));
        assertThat(DesktopRecipeValidator.validate(good).steps().get(1).text())
                .isEqualTo("{{secret.DESKTOP_PW}}");

        DesktopRecipeSpec badTarget =
                new DesktopRecipeSpec(
                        DesktopRecipeSpec.CURRENT_VERSION,
                        List.of(),
                        List.of(),
                        List.of(
                                step(
                                        DesktopRecipeSpec.OPEN,
                                        null,
                                        null,
                                        null,
                                        "{{secret.DESKTOP_PW}}",
                                        null,
                                        null)));
        assertThatThrownBy(() -> DesktopRecipeValidator.validate(badTarget))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret");

        DesktopRecipeSpec badLabel =
                new DesktopRecipeSpec(
                        DesktopRecipeSpec.CURRENT_VERSION,
                        List.of("safari"),
                        List.of(),
                        List.of(
                                activate("Safari"),
                                step(
                                        DesktopRecipeSpec.PRESS,
                                        null,
                                        "{{secret.DESKTOP_PW}}",
                                        null,
                                        null,
                                        null,
                                        null)));
        assertThatThrownBy(() -> DesktopRecipeValidator.validate(badLabel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret");
    }

    @Test
    void undeclaredAndMalformedPlaceholdersAreRefused() {
        DesktopRecipeSpec undeclared =
                new DesktopRecipeSpec(
                        DesktopRecipeSpec.CURRENT_VERSION,
                        List.of(),
                        List.of(),
                        List.of(
                                step(
                                        DesktopRecipeSpec.OPEN,
                                        null,
                                        null,
                                        null,
                                        "file:///tmp/{{which}}",
                                        null,
                                        null)));
        assertThatThrownBy(() -> DesktopRecipeValidator.validate(undeclared))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("undeclared parameter");

        DesktopRecipeSpec malformed =
                new DesktopRecipeSpec(
                        DesktopRecipeSpec.CURRENT_VERSION,
                        List.of(),
                        List.of(),
                        List.of(
                                step(
                                        DesktopRecipeSpec.OPEN,
                                        null,
                                        null,
                                        null,
                                        "file:///tmp/{{",
                                        null,
                                        null)));
        assertThatThrownBy(() -> DesktopRecipeValidator.validate(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void eachActionNeedsItsFields() {
        assertThatThrownBy(
                        () ->
                                DesktopRecipeValidator.validate(
                                        spec(
                                                List.of(),
                                                List.of(
                                                        step(
                                                                DesktopRecipeSpec.OPEN,
                                                                null,
                                                                null,
                                                                null,
                                                                null,
                                                                null,
                                                                null)))))
                .hasMessageContaining("target");

        RecipeStep clickNoY =
                new RecipeStep(
                        DesktopRecipeSpec.CLICK,
                        null,
                        null,
                        null,
                        10,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false);
        assertThatThrownBy(
                        () ->
                                DesktopRecipeValidator.validate(
                                        spec(List.of("a"), List.of(activate("a"), clickNoY))))
                .hasMessageContaining("x and y");

        assertThatThrownBy(
                        () ->
                                DesktopRecipeValidator.validate(
                                        spec(
                                                List.of("a"),
                                                List.of(
                                                        activate("a"),
                                                        step(
                                                                DesktopRecipeSpec.KEY,
                                                                null,
                                                                null,
                                                                "hyperdrive",
                                                                null,
                                                                null,
                                                                null)))))
                .hasMessageContaining("key");

        assertThatThrownBy(
                        () ->
                                DesktopRecipeValidator.validate(
                                        spec(
                                                List.of("a"),
                                                List.of(
                                                        activate("a"),
                                                        step(
                                                                DesktopRecipeSpec.WAIT,
                                                                null,
                                                                null,
                                                                null,
                                                                null,
                                                                null,
                                                                0)))))
                .hasMessageContaining("waitMs");

        assertThatThrownBy(
                        () ->
                                DesktopRecipeValidator.validate(
                                        spec(
                                                List.of("a"),
                                                List.of(
                                                        step(
                                                                "teleport",
                                                                "a",
                                                                null,
                                                                null,
                                                                null,
                                                                null,
                                                                null)))))
                .hasMessageContaining("unknown action");
    }

    @Test
    void stepTimeoutsAreBounded() {
        RecipeStep tooLong =
                new RecipeStep(
                        DesktopRecipeSpec.ACTIVATE,
                        "a",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        99_999,
                        false);
        assertThatThrownBy(
                        () -> DesktopRecipeValidator.validate(spec(List.of("a"), List.of(tooLong))))
                .hasMessageContaining("timeoutMs");
    }

    @Test
    void anExpectedAppMustAlsoBeAllowed() {
        RecipeStep step =
                new RecipeStep(
                        DesktopRecipeSpec.ACTIVATE,
                        "safari",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new Expect(null, "Terminal"),
                        null,
                        false);
        assertThatThrownBy(
                        () ->
                                DesktopRecipeValidator.validate(
                                        spec(List.of("safari"), List.of(step))))
                .hasMessageContaining("outside the recipe's allowed apps");
    }

    @Test
    void reservedAndDuplicateParamsAreRefused() {
        DesktopRecipeSpec reserved =
                new DesktopRecipeSpec(
                        DesktopRecipeSpec.CURRENT_VERSION,
                        List.of("a"),
                        List.of(new ParamSpec("secret", true)),
                        List.of(activate("a")));
        assertThatThrownBy(() -> DesktopRecipeValidator.validate(reserved))
                .hasMessageContaining("reserved");

        DesktopRecipeSpec dup =
                new DesktopRecipeSpec(
                        DesktopRecipeSpec.CURRENT_VERSION,
                        List.of("a"),
                        List.of(new ParamSpec("city", true), new ParamSpec("CITY", false)),
                        List.of(activate("a")));
        assertThatThrownBy(() -> DesktopRecipeValidator.validate(dup))
                .hasMessageContaining("twice");
    }
}
