package com.agenticai.core.agents.tools.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ToolCallScope;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@code run_desktop_recipe} over a mocked {@link DesktopRecipeService}: the flag-off refusal, the
 * ToolCallScope agent binding, the name/id lookup, argument forwarding (including "omitted mode =
 * the recipe's stored default"), the output shape, the caller-side {@code recordRun} bookkeeping
 * and the confirmation card.
 */
class RunDesktopRecipeToolCallbackTest {

    private static final UUID AGENT = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private DesktopRecipeService service;
    private RunDesktopRecipeToolCallback tool;
    private DesktopRecipe recipe;

    @BeforeEach
    void setUp() {
        service = mock(DesktopRecipeService.class);
        when(service.isEnabled()).thenReturn(true);
        tool = new RunDesktopRecipeToolCallback(service);
        recipe =
                new DesktopRecipe(
                        AGENT,
                        "Tidy desk",
                        "open Notes and type",
                        List.of("Notes"),
                        DesktopRecipe.MODE_AUTO,
                        "{}",
                        2,
                        "tester",
                        Instant.parse("2026-01-01T00:00:00Z"));
    }

    private void runnable() {
        when(service.findRunnable(eq(AGENT), anyString())).thenReturn(Optional.of(recipe));
    }

    private void runs(DesktopRecipeReplayer.Outcome outcome) {
        when(service.replayForAgent(
                        eq(AGENT), anyString(), anyMap(), anyBoolean(), anyInt(), anyBoolean()))
                .thenReturn(outcome);
    }

    private static DesktopRecipeReplayer.Outcome finished(
            boolean ok, int ran, int total, String failure) {
        return new DesktopRecipeReplayer.Outcome(
                ok,
                ran,
                total,
                failure,
                List.of(
                        new DesktopRecipeReplayer.StepResult(1, "open", true, "Notes"),
                        new DesktopRecipeReplayer.StepResult(
                                2, "type", ok, ok ? "filled" : "field not focused")));
    }

    /** Runs the tool the way a real call reaches it: inside the guard's {@link ToolCallScope}. */
    private String call(RunDesktopRecipeToolCallback callback, String json) {
        try (ToolCallScope.Scope ignored =
                ToolCallScope.open(
                        RunDesktopRecipeToolCallback.NAME, AGENT, "sess-1", null, null)) {
            return callback.call(json);
        }
    }

    private static JsonNode parse(String json) throws Exception {
        return JsonMappers.plain().readTree(json);
    }

    // ---- gating and identity ----------------------------------------------------------

    @Test
    void aDisabledServiceAnswersAnErrorAndNeverRuns() {
        when(service.isEnabled()).thenReturn(false);

        assertThat(call(tool, "{\"recipe\":\"x\"}")).contains("error").contains("not enabled");
        verify(service, never())
                .replayForAgent(any(), anyString(), anyMap(), anyBoolean(), anyInt(), anyBoolean());
    }

    @Test
    void aCallWithNoToolScopeHasNoAgentAndIsRefused() {
        assertThat(tool.call("{\"recipe\":\"x\"}")).contains("error").contains("agent");
        verify(service, never())
                .replayForAgent(any(), anyString(), anyMap(), anyBoolean(), anyInt(), anyBoolean());
    }

    @Test
    void theRecipeArgumentIsRequired() {
        assertThat(call(tool, "{}")).contains("error").contains("recipe");
        verify(service, never())
                .replayForAgent(any(), anyString(), anyMap(), anyBoolean(), anyInt(), anyBoolean());
    }

    @Test
    void anUnknownRecipeIsAnErrorNotAReplay() {
        when(service.findRunnable(eq(AGENT), anyString())).thenReturn(Optional.empty());

        assertThat(call(tool, "{\"recipe\":\"missing\"}")).contains("error").contains("missing");
        verify(service, never())
                .replayForAgent(any(), anyString(), anyMap(), anyBoolean(), anyInt(), anyBoolean());
    }

    @Test
    void anAmbiguousNameReportsTheServiceMessage() {
        when(service.findRunnable(eq(AGENT), anyString()))
                .thenThrow(new IllegalArgumentException("more than one desktop recipe is named"));

        assertThat(call(tool, "{\"recipe\":\"dup\"}")).contains("error").contains("more than one");
        verify(service, never())
                .replayForAgent(any(), anyString(), anyMap(), anyBoolean(), anyInt(), anyBoolean());
    }

    @Test
    void anUnknownModeIsAnErrorNotAReplay() {
        runnable();

        assertThat(call(tool, "{\"recipe\":\"x\",\"mode\":\"TURBO\"}"))
                .contains("error")
                .contains("AUTO")
                .contains("MONITOR");
        verify(service, never())
                .replayForAgent(any(), anyString(), anyMap(), anyBoolean(), anyInt(), anyBoolean());
    }

    // ---- running ------------------------------------------------------------------------

    @Test
    void aSuccessfulRunReportsTheRecipeAndEachStep() throws Exception {
        runnable();
        runs(finished(true, 2, 2, null));

        JsonNode r = parse(call(tool, "{\"recipe\":\"Tidy desk\"}"));

        assertThat(r.path("ok").asBoolean()).isTrue();
        assertThat(r.path("mode").asText()).isEqualTo("AUTO");
        assertThat(r.path("stepsRun").asInt()).isEqualTo(2);
        assertThat(r.path("recipe").path("id").asText()).isEqualTo(recipe.getId().toString());
        assertThat(r.path("recipe").path("name").asText()).isEqualTo("Tidy desk");
        assertThat(r.path("recipe").path("steps").asInt()).isEqualTo(2);
        assertThat(r.path("stepResults")).hasSize(2);
        assertThat(r.path("stepResults").get(1).path("action").asText()).isEqualTo("type");
        assertThat(r.path("failure").isMissingNode()).isTrue();
    }

    @Test
    void monitorConfirmationAndResumeAreForwardedVerbatim() {
        runnable();
        runs(finished(true, 1, 2, null));
        ArgumentCaptor<Boolean> irreversible = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Integer> fromStep = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Boolean> monitor = ArgumentCaptor.forClass(Boolean.class);

        call(
                tool,
                "{\"recipe\":\"x\",\"mode\":\"monitor\",\"confirmIrreversible\":true,"
                        + "\"fromStep\":2,\"params\":{\"doc\":\"report\"}}");

        verify(service)
                .replayForAgent(
                        eq(AGENT),
                        eq(recipe.getId().toString()),
                        anyMap(),
                        irreversible.capture(),
                        fromStep.capture(),
                        monitor.capture());
        assertThat(irreversible.getValue()).isTrue();
        assertThat(fromStep.getValue()).isEqualTo(2);
        assertThat(monitor.getValue()).isTrue();
    }

    @Test
    void anOmittedModeFallsBackToTheRecipesStoredDefault() {
        runnable();
        runs(finished(true, 2, 2, null));
        recipe.edit(null, null, DesktopRecipe.MODE_MONITOR, Instant.now());
        ArgumentCaptor<Boolean> monitor = ArgumentCaptor.forClass(Boolean.class);

        call(tool, "{\"recipe\":\"Tidy desk\"}");

        verify(service)
                .replayForAgent(
                        eq(AGENT),
                        anyString(),
                        anyMap(),
                        anyBoolean(),
                        anyInt(),
                        monitor.capture());
        assertThat(monitor.getValue()).isTrue();
    }

    @Test
    void aFailureReportsTheFailureAndEachStepNote() throws Exception {
        runnable();
        runs(finished(false, 1, 2, "step 2: field not focused"));

        JsonNode r = parse(call(tool, "{\"recipe\":\"x\"}"));

        assertThat(r.path("ok").asBoolean()).isFalse();
        assertThat(r.path("failure").asText()).contains("step 2");
        assertThat(r.path("stepsRun").asInt()).isEqualTo(1);
        assertThat(r.path("stepResults").get(1).path("note").asText())
                .contains("field not focused");
    }

    @Test
    void aServiceExceptionBecomesAnErrorJsonNeverAThrow() {
        runnable();
        when(service.replayForAgent(
                        eq(AGENT), anyString(), anyMap(), anyBoolean(), anyInt(), anyBoolean()))
                .thenThrow(new IllegalStateException("sidecar offline"));

        assertThat(call(tool, "{\"recipe\":\"x\"}")).contains("error");
    }

    // ---- run bookkeeping ------------------------------------------------------------------

    @Test
    void aFailedRunIsRecordedUnderTheRecipeId() {
        runnable();
        runs(finished(false, 1, 2, "step 2: field not focused"));

        call(tool, "{\"recipe\":\"Tidy desk\"}");

        verify(service).recordRun(eq(recipe.getId()), eq("step 2: field not focused"));
    }

    @Test
    void aSuccessRecordsNoFailure() {
        runnable();
        runs(finished(true, 2, 2, null));

        call(tool, "{\"recipe\":\"Tidy desk\"}");

        verify(service).recordRun(eq(recipe.getId()), isNull());
    }

    // ---- confirmation card and definition ---------------------------------------------------

    @Test
    void theConfirmationCardNamesTheRecipeStepsAndMode() {
        when(service.findRunnable(isNull(), eq("Tidy desk"))).thenReturn(Optional.of(recipe));

        assertThat(tool.describeForConfirmation("{\"recipe\":\"Tidy desk\",\"mode\":\"MONITOR\"}"))
                .isEqualTo("Run desktop recipe 'Tidy desk' (2 steps, mode MONITOR)");
        // Mode omitted: the recipe's stored default is shown.
        assertThat(tool.describeForConfirmation("{\"recipe\":\"Tidy desk\"}"))
                .isEqualTo("Run desktop recipe 'Tidy desk' (2 steps, mode AUTO)");
    }

    @Test
    void theConfirmationCardSurvivesAMissingRecipeAndGarbageInput() {
        when(service.findRunnable(isNull(), anyString())).thenReturn(Optional.empty());

        assertThat(tool.describeForConfirmation("{\"recipe\":\"x\"}"))
                .isEqualTo("Run desktop recipe 'x' (mode AUTO)");
        assertThat(tool.describeForConfirmation("{}")).isNull();
        assertThat(tool.describeForConfirmation("garbage")).isNull();
    }

    @Test
    void theDefinitionIsNamedCarriesTheSchemaAndStaysShort() {
        var def = tool.getToolDefinition();

        assertThat(def.name()).isEqualTo("run_desktop_recipe");
        assertThat(def.description().length()).isLessThan(700);
        assertThat(def.inputSchema()).contains("confirmIrreversible").contains("MONITOR");
    }
}
