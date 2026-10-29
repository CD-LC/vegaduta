package com.agenticai.core.agents.tools.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.tools.desktop.DesktopRecipeRecorder.JournalCall;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeRecorder.Recorded;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeSpec.RecipeStep;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The journal-to-spec half of run-to-script: which calls are kept, how user values become {@code
 * {{params}}}, why literal typed text refuses without an explicit opt-in, and which steps get
 * flagged irreversible.
 */
class DesktopRecipeRecorderTest {

    private static final String OK = "{\"ok\":true}";

    private static JournalCall call(String tool, String input) {
        return new JournalCall(tool, input, OK, null);
    }

    private static JournalCall failed(String tool, String input) {
        return new JournalCall(tool, input, "{\"error\":\"no UI element\"}", null);
    }

    @Test
    void recordsASuccessfulRunInOrder() {
        Recorded r =
                DesktopRecipeRecorder.record(
                        List.of(
                                call("local_desktop_open", "{\"target\":\"Notes\"}"),
                                call("local_desktop_activate", "{\"app\":\"Notes\"}"),
                                call("local_desktop_press", "{\"label\":\"New Note\"}"),
                                call("local_desktop_type", "{\"text\":\"shopping list\"}"),
                                call("local_desktop_key", "{\"key\":\"tab\"}")),
                        Map.of(),
                        true);

        List<RecipeStep> steps = r.spec().steps();
        assertThat(steps).hasSize(5);
        assertThat(steps.get(0).action()).isEqualTo(DesktopRecipeSpec.OPEN);
        assertThat(steps.get(0).target()).isEqualTo("Notes");
        assertThat(steps.get(1).action()).isEqualTo(DesktopRecipeSpec.ACTIVATE);
        assertThat(steps.get(1).app()).isEqualTo("Notes");
        assertThat(steps.get(2).action()).isEqualTo(DesktopRecipeSpec.PRESS);
        assertThat(steps.get(2).label()).isEqualTo("New Note");
        assertThat(steps.get(3).action()).isEqualTo(DesktopRecipeSpec.TYPE);
        assertThat(steps.get(3).text()).isEqualTo("shopping list");
        assertThat(steps.get(4).action()).isEqualTo(DesktopRecipeSpec.KEY);
        assertThat(steps.get(4).key()).isEqualTo("tab");
        assertThat(r.spec().allowedApps()).containsExactly("notes");
    }

    @Test
    void dropsReadsNotificationsAndFailedCalls() {
        Recorded r =
                DesktopRecipeRecorder.record(
                        List.of(
                                call("local_desktop_open", "{\"target\":\"Notes\"}"),
                                call("local_desktop_screen", "{}"),
                                call("local_desktop_elements", "{}"),
                                failed("local_desktop_press", "{\"label\":\"Missing\"}"),
                                call("local_desktop_notify", "{\"title\":\"t\",\"body\":\"b\"}"),
                                call("local_desktop_clipboard", "{}"),
                                call("local_desktop_key", "{\"key\":\"tab\"}")),
                        Map.of(),
                        true);
        assertThat(r.spec().steps())
                .extracting(DesktopRecipeSpec.RecipeStep::action)
                .containsExactly(DesktopRecipeSpec.OPEN, DesktopRecipeSpec.KEY);
    }

    @Test
    void suppliedValuesBecomeParametersEverywhereTheyAppear() {
        Recorded r =
                DesktopRecipeRecorder.record(
                        List.of(
                                call(
                                        "local_desktop_open",
                                        "{\"target\":\"https://shop.example/orders?q=Paris\"}"),
                                call("local_desktop_activate", "{\"app\":\"Safari\"}"),
                                call("local_desktop_type", "{\"text\":\"Paris\"}")),
                        Map.of("city", "Paris"),
                        false);

        RecipeStep open = r.spec().steps().get(0);
        assertThat(open.target()).isEqualTo("https://shop.example/orders?q={{city}}");
        RecipeStep type = r.spec().steps().get(2);
        assertThat(type.text()).isEqualTo("{{city}}");
        assertThat(r.spec().params()).hasSize(1);
        assertThat(r.spec().params().get(0).name()).isEqualTo("city");
        // The literal is gone - a stored recipe can never replay a user-supplied value back.
        assertThat(r.spec().steps().toString()).doesNotContain("Paris");
    }

    @Test
    void aLiteralTypedTextAbortsUnlessExplicitlyAllowed() {
        List<JournalCall> calls =
                List.of(
                        call("local_desktop_open", "{\"target\":\"Notes\"}"),
                        call("local_desktop_activate", "{\"app\":\"Notes\"}"),
                        call("local_desktop_type", "{\"text\":\"remember the milk\"}"));

        assertThatThrownBy(() -> DesktopRecipeRecorder.record(calls, Map.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("typed text");

        Recorded allowed = DesktopRecipeRecorder.record(calls, Map.of(), true);
        assertThat(allowed.spec().steps().get(2).text()).isEqualTo("remember the milk");
        assertThat(allowed.warnings()).anyMatch(w -> w.contains("literal"));
    }

    @Test
    void aSecretPlaceholderSurvivesRecording() {
        Recorded r =
                DesktopRecipeRecorder.record(
                        List.of(
                                call("local_desktop_open", "{\"target\":\"Notes\"}"),
                                call("local_desktop_activate", "{\"app\":\"Notes\"}"),
                                call("local_desktop_type", "{\"text\":\"{{secret.DESKTOP_PW}}\"}")),
                        Map.of(),
                        false);
        assertThat(r.spec().steps().get(2).text()).isEqualTo("{{secret.DESKTOP_PW}}");
    }

    @Test
    void irreversibleHeuristicsMarkPressAndReturnButNotOpen() {
        Recorded r =
                DesktopRecipeRecorder.record(
                        List.of(
                                call("local_desktop_open", "{\"target\":\"https://bank.example\"}"),
                                call("local_desktop_activate", "{\"app\":\"Safari\"}"),
                                call("local_desktop_press", "{\"label\":\"Delete account\"}"),
                                call("local_desktop_press", "{\"label\":\"Back\"}"),
                                call("local_desktop_key", "{\"key\":\"return\"}")),
                        Map.of(),
                        true);
        List<RecipeStep> steps = r.spec().steps();
        assertThat(steps.get(0).irreversible()).isFalse();
        assertThat(steps.get(2).irreversible()).isTrue();
        assertThat(steps.get(3).irreversible()).isFalse();
        assertThat(steps.get(4).irreversible()).isTrue();
        assertThat(r.warnings()).anyMatch(w -> w.contains("irreversible") || w.contains("submit"));
    }

    @Test
    void aRunThatDoesNotEstablishAnAppCannotBeRecorded() {
        assertThatThrownBy(
                        () ->
                                DesktopRecipeRecorder.record(
                                        List.of(call("local_desktop_click", "{\"x\":10,\"y\":20}")),
                                        Map.of(),
                                        true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("open or activate");
    }

    @Test
    void aRunWithNoDesktopActionsCannotBeRecorded() {
        assertThatThrownBy(
                        () ->
                                DesktopRecipeRecorder.record(
                                        List.of(call("local_desktop_screen", "{}")),
                                        Map.of(),
                                        true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nothing to record");
    }

    @Test
    void aRightClickIsKeptWithAWarning() {
        Recorded r =
                DesktopRecipeRecorder.record(
                        List.of(
                                call("local_desktop_activate", "{\"app\":\"Finder\"}"),
                                call(
                                        "local_desktop_click",
                                        "{\"x\":10,\"y\":20,\"button\":\"right\"}")),
                        Map.of(),
                        true);
        assertThat(r.spec().steps()).hasSize(2);
        assertThat(r.warnings()).anyMatch(w -> w.contains("right"));
    }

    @Test
    void anAppNameContainingAParameterValueIsNotSubstituted() {
        Recorded r =
                DesktopRecipeRecorder.record(
                        List.of(call("local_desktop_activate", "{\"app\":\"Notes Pro\"}")),
                        Map.of("app_name", "Notes Pro"),
                        true);
        assertThat(r.spec().steps().get(0).app()).isEqualTo("Notes Pro");
        assertThat(r.warnings()).anyMatch(w -> w.contains("app names stay literal"));
    }

    @Test
    void unusedParametersAreReportedNotStored() {
        Recorded r =
                DesktopRecipeRecorder.record(
                        List.of(call("local_desktop_activate", "{\"app\":\"Notes\"}")),
                        Map.of("unused", "nothing matches this"),
                        true);
        assertThat(r.spec().params()).isEmpty();
        assertThat(r.warnings()).anyMatch(w -> w.contains("unused"));
    }
}
