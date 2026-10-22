package com.agenticai.core.agents.tools.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.TaskRequest;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.TaskResponse;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskService;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskSettings;
import com.agenticai.core.agents.tools.classification.BuiltInToolClassifications;
import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolDeterminism;
import com.agenticai.core.agents.tools.classification.ToolSensitivity;
import com.agenticai.core.agents.tools.classification.ToolTrustTier;
import com.agenticai.core.guardrails.ToolConfirmationDescriber;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * schedule_task: the model proposes (arguments in), the service validates and creates (identity
 * never from the arguments), failures come back as text, and the confirmation card text is built
 * from the validated schedule.
 */
class ScheduleTaskToolCallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UUID agentId = UUID.randomUUID();
    private ScheduledTaskService service;
    private ScheduleTaskToolCallback tool;

    @BeforeEach
    void setUp() {
        service = mock(ScheduledTaskService.class);
        ScheduledTaskSettings settings =
                new ScheduledTaskSettings(
                        true, true, 10, 3, 15, 3, 2000, 720, true, 3500, "in_app,whatsapp");
        tool = new ScheduleTaskToolCallback(service, settings, agentId);
    }

    private TaskResponse response() {
        return new TaskResponse(
                UUID.randomUUID(),
                agentId,
                "Morning digest",
                "p",
                "weekdays",
                "08:00",
                null,
                "0 0 8 * * MON-FRI",
                "Asia/Kolkata",
                "Every weekday (Monday to Friday) at 08:00 (Asia/Kolkata)",
                "whatsapp",
                "+919876543210",
                true,
                null,
                Instant.parse("2026-10-05T02:30:00Z"),
                List.of(
                        Instant.parse("2026-10-05T02:30:00Z"),
                        Instant.parse("2026-10-06T02:30:00Z")),
                null,
                null,
                null,
                0,
                0L,
                null,
                null,
                "user-1",
                Instant.parse("2026-10-04T00:00:00Z"),
                Instant.parse("2026-10-04T00:00:00Z"));
    }

    private static final String ARGS =
            """
        {"name": "Morning digest", "prompt": "Summarise my unread email and send me the top 5.",
         "frequency": "weekdays", "time": "08:00", "timezone": "Asia/Kolkata",
         "delivery": "whatsapp", "deliveryTo": "+919876543210"}
        """;

    // ----------------------------------------------------------------- definition

    @Test
    void theToolIsNamedAndSchemaedForAnEnumeratedFriendlySchedule() throws Exception {
        assertThat(tool.getToolDefinition().name()).isEqualTo("schedule_task");
        JsonNode schema = MAPPER.readTree(tool.getToolDefinition().inputSchema());
        assertThat(schema.get("required"))
                .extracting(JsonNode::asText)
                .containsExactly("name", "prompt", "frequency");
        assertThat(schema.at("/properties/frequency/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("daily", "weekdays", "weekly", "monthly", "custom");
        assertThat(schema.at("/properties/delivery/enum"))
                .extracting(JsonNode::asText)
                .containsExactly(
                        "in_app",
                        "email",
                        "whatsapp",
                        "whatsapp_qr",
                        "telegram",
                        "slack",
                        "signal",
                        "matrix",
                        "imessage",
                        "a2a",
                        "voice");
        // Identity is never an argument.
        assertThat(schema.at("/properties").has("agentId")).isFalse();
        assertThat(schema.at("/properties").has("owner")).isFalse();
        // Tool schemas are token-budgeted: the description stays short.
        assertThat(tool.getToolDefinition().description().length()).isLessThan(400);
    }

    @Test
    void theToolIsClassifiedADurableTenantWriteThatRequiresHumanConfirmation() {
        ToolClassification c = BuiltInToolClassifications.all().get("schedule_task");
        assertThat(c).isNotNull();
        assertThat(c.trust()).isEqualTo(ToolTrustTier.TENANT_SCOPED);
        assertThat(c.sensitivity()).isEqualTo(ToolSensitivity.WRITE_TENANT);
        assertThat(c.determinism()).isEqualTo(ToolDeterminism.DURABLE);
        assertThat(c.irreversible()).isTrue();
        assertThat(c.requiresHumanConfirmation()).isTrue();
        assertThat(c.isTaintSink()).isTrue();
        assertThat(c.needsToolContext()).isTrue();
        assertThat(tool).isInstanceOf(ToolConfirmationDescriber.class);
    }

    // --------------------------------------------------------------------- call

    @Test
    void aValidCallCreatesTheTaskForTheAgentTheToolWasBuiltForAndReportsNextRuns()
            throws Exception {
        when(service.create(any())).thenReturn(response());

        String out = tool.call(ARGS);

        ArgumentCaptor<TaskRequest> request = ArgumentCaptor.forClass(TaskRequest.class);
        verify(service).create(request.capture());
        TaskRequest r = request.getValue();
        assertThat(r.agentId()).isEqualTo(agentId);
        assertThat(r.name()).isEqualTo("Morning digest");
        assertThat(r.frequency()).isEqualTo("weekdays");
        assertThat(r.time()).isEqualTo("08:00");
        assertThat(r.timezone()).isEqualTo("Asia/Kolkata");
        assertThat(r.delivery()).isEqualTo("whatsapp");
        assertThat(r.deliveryTo()).isEqualTo("+919876543210");
        JsonNode json = MAPPER.readTree(out);
        assertThat(json.get("scheduled").asBoolean()).isTrue();
        assertThat(json.get("when").asText()).contains("Every weekday");
        assertThat(json.get("nextRuns")).hasSize(2);
    }

    @Test
    void anAgentIdSmuggledInTheArgumentsIsIgnored() {
        when(service.create(any())).thenReturn(response());
        UUID other = UUID.randomUUID();

        tool.call(
                "{\"name\":\"n\",\"prompt\":\"p\",\"frequency\":\"daily\",\"time\":\"08:00\",\"agentId\":\""
                        + other
                        + "\",\"owner\":\"admin\"}");

        ArgumentCaptor<TaskRequest> request = ArgumentCaptor.forClass(TaskRequest.class);
        verify(service).create(request.capture());
        assertThat(request.getValue().agentId()).isEqualTo(agentId);
    }

    @Test
    void optionalNumbersAndDatesAreParsed() {
        when(service.create(any())).thenReturn(response());

        tool.call(
                "{\"name\":\"n\",\"prompt\":\"p\",\"frequency\":\"daily\",\"time\":\"08:00\",\"maxRuns\":7,\"expiresAt\":\"2026-12-31T00:00:00Z\"}");

        ArgumentCaptor<TaskRequest> request = ArgumentCaptor.forClass(TaskRequest.class);
        verify(service).create(request.capture());
        assertThat(request.getValue().maxRuns()).isEqualTo(7);
        assertThat(request.getValue().expiresAt()).isEqualTo(Instant.parse("2026-12-31T00:00:00Z"));
    }

    @Test
    void validationFailuresComeBackAsTextForTheModelToRelayNotAsExceptions() {
        when(service.create(any()))
                .thenThrow(new IllegalArgumentException("Invalid schedule: time must be HH:mm"));
        assertThat(tool.call(ARGS))
                .isEqualTo("Could not schedule the task: Invalid schedule: time must be HH:mm");

        doThrow(new TenantLimitExceededException("maximum of 3 scheduled tasks"))
                .when(service)
                .create(any());
        assertThat(tool.call(ARGS)).contains("maximum of 3");

        doThrow(new NoSuchElementException("gone")).when(service).create(any());
        assertThat(tool.call(ARGS)).startsWith("Could not schedule the task:");

        doThrow(new ResourceAccessDeniedException()).when(service).create(any());
        assertThat(tool.call(ARGS)).contains("do not have access");
    }

    @Test
    void anUnexpectedErrorNeverLeaksItsMessageToTheModel() {
        when(service.create(any()))
                .thenThrow(
                        new IllegalStateException(
                                "jdbc:postgresql://secret-host/db password=hunter2"));

        String out = tool.call(ARGS);

        assertThat(out)
                .contains("Nothing was created")
                .doesNotContain("secret-host")
                .doesNotContain("hunter2");
    }

    @Test
    void malformedArgumentsNeverReachTheService() {
        assertThat(tool.call("not json")).contains("not valid JSON");
        assertThat(tool.call("[1,2]")).contains("must be a JSON object");
        assertThat(tool.call("")).contains("required");
        assertThat(
                        tool.call(
                                "{\"name\":\"n\",\"prompt\":\"p\",\"frequency\":\"daily\",\"maxRuns\":\"many\"}"))
                .contains("maxRuns");
        assertThat(
                        tool.call(
                                "{\"name\":\"n\",\"prompt\":\"p\",\"frequency\":\"daily\",\"expiresAt\":\"tomorrow\"}"))
                .contains("expiresAt");
        verifyNoInteractions(service);
    }

    // ---------------------------------------------------------- confirmation card

    @Test
    void theConfirmationCardShowsTheScheduleInPlainLanguageAndTheNextThreeRuns() {
        String card = tool.describeForConfirmation(ARGS);

        assertThat(card)
                .contains("Morning digest")
                .contains("Every weekday (Monday to Friday) at 08:00 (Asia/Kolkata)")
                .contains("whatsapp (+919876543210)")
                .contains("Summarise my unread email");
        String nextRuns =
                card.lines().filter(l -> l.startsWith("Next runs:")).findFirst().orElseThrow();
        assertThat(nextRuns.split(";")).hasSize(3);
        assertThat(nextRuns).contains("08:00 IST");
    }

    @Test
    void theCardForAnInvalidScheduleSaysSoInsteadOfFailingTheGate() {
        String card =
                tool.describeForConfirmation(
                        "{\"name\":\"n\",\"prompt\":\"p\",\"frequency\":\"custom\",\"cron\":\"* * * * *\"}");

        assertThat(card).contains("not valid yet").contains("every 15 minutes");
    }

    @Test
    void anUnreadableCallFallsBackToTheDefaultSummary() {
        assertThat(tool.describeForConfirmation("not json")).isNull();
        assertThat(tool.describeForConfirmation(null)).isNull();
    }
}
