package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.dto.GenerateGraphResponse.RequirementItem;
import com.agenticai.core.workflow.dto.GenerateGraphResponse.Requirements;
import com.agenticai.core.workflow.dto.GenerateGraphResponse.SuggestedSchedule;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkflowGeneratorSupportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    private static WorkflowNodeDto node(String id, String type, Map<String, Object> config) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("label", id, "config", config));
    }

    // --- cron ---

    @Test
    void cronSupportNormalisesAndValidates() {
        assertEquals("0 0 9 * * 1", WorkflowCronSupport.normalize("0 9 * * 1"));
        assertEquals("0 0 9 * * 1", WorkflowCronSupport.normalize("  0 0 9 * * 1 "));
        assertNull(WorkflowCronSupport.normalize(null));
        assertEquals("0 9 * * 1", WorkflowCronSupport.validFiveField("0  9 * * 1").orElseThrow());
        assertEquals(
                "30 8 * * *",
                WorkflowCronSupport.validFiveField("0 30 8 * * *").orElseThrow(),
                "6-field with 0 seconds");
        assertTrue(WorkflowCronSupport.validFiveField("every monday").isEmpty());
        assertTrue(WorkflowCronSupport.validFiveField("99 9 * * 1").isEmpty());
        assertTrue(
                WorkflowCronSupport.validFiveField("15 30 8 * * *").isEmpty(),
                "non-zero seconds cannot be expressed in 5 fields");
        assertTrue(WorkflowCronSupport.validFiveField("").isEmpty());
        assertTrue(WorkflowCronSupport.validFiveField(null).isEmpty());
    }

    // --- schedule ---

    @Test
    void scheduleIsReadFromTheTopLevelAndAlwaysUtc() throws Exception {
        List<String> notes = new ArrayList<>();
        SuggestedSchedule schedule =
                WorkflowGeneratorSupport.parseSchedule(
                        json(
                                "{\"schedule\": {\"cron\": \"0 9 * * 1\", \"label\": \"Every Monday 09:00\"}}"),
                        notes);
        assertEquals(new SuggestedSchedule("0 9 * * 1", "Every Monday 09:00", "UTC"), schedule);
        assertTrue(notes.isEmpty());

        SuggestedSchedule unlabelled =
                WorkflowGeneratorSupport.parseSchedule(
                        json("{\"schedule\": {\"cron\": \"0 * * * *\"}}"), notes);
        assertEquals("0 * * * *", unlabelled.label(), "the cron stands in for a missing label");
    }

    @Test
    void anUnusableCronIsDroppedWithANoteAndAbsenceIsSilent() throws Exception {
        List<String> notes = new ArrayList<>();
        assertNull(
                WorkflowGeneratorSupport.parseSchedule(
                        json("{\"schedule\": {\"cron\": \"mondays at nine\"}}"), notes));
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains("schedule"), notes.get(0));

        notes.clear();
        assertNull(WorkflowGeneratorSupport.parseSchedule(json("{\"graph\": {}}"), notes));
        assertNull(
                WorkflowGeneratorSupport.parseSchedule(json("{\"schedule\": \"weekly\"}"), notes));
        assertNull(
                WorkflowGeneratorSupport.parseSchedule(
                        json("{\"schedule\": {\"cron\": \"\"}}"), notes));
        assertNull(WorkflowGeneratorSupport.parseSchedule(null, notes));
        assertTrue(notes.isEmpty(), "no schedule asked for is not worth a note");
    }

    // --- side arrays ---

    @Test
    void stringsAreTrimmedDeduplicatedAndCapped() throws Exception {
        JsonNode root = json("{\"notes\": [\" a \", \"a\", \"\", 7, \"b\", \"c\"]}");
        assertEquals(List.of("a", "b"), WorkflowGeneratorSupport.strings(root, "notes", 2));
        assertTrue(WorkflowGeneratorSupport.strings(root, "missing", 5).isEmpty());
        assertTrue(WorkflowGeneratorSupport.strings(null, "notes", 5).isEmpty());
    }

    // --- requirements ---

    @Test
    void secretsComeFromTheGraphAndFromWellFormedModelNames() {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node("t", "trigger", Map.of()),
                                node(
                                        "h",
                                        "http",
                                        Map.of(
                                                "url",
                                                "https://x.example/{{secret.PATH_TOKEN}}",
                                                "headers",
                                                List.of(
                                                        Map.of(
                                                                "name",
                                                                "Authorization",
                                                                "value",
                                                                "Bearer {{ secret.API_KEY }}"))))),
                        List.of());
        assertEquals(
                Set.of("PATH_TOKEN", "API_KEY"),
                Set.copyOf(WorkflowGeneratorSupport.secretRefs(graph)));

        Requirements req =
                WorkflowGeneratorSupport.requirements(
                        graph,
                        List.of("api_key", "CRM_TOKEN", "not a name!", "sk-live-123"),
                        List.of("price list"),
                        null,
                        null);
        assertEquals(Set.of("PATH_TOKEN", "API_KEY", "CRM_TOKEN"), Set.copyOf(req.secrets()));
        assertEquals(3, req.secrets().size());
        assertEquals(List.of("price list"), req.knowledge());
        assertEquals(3, req.items().size());
        assertTrue(
                req.items().stream()
                        .allMatch(i -> "secret".equals(i.kind()) && "/secrets".equals(i.href())));
    }

    @Test
    void unpickedAgentsAndCollectionsAndDisconnectedWhatsAppBecomeItems() {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node("t", "trigger", Map.of()),
                                node("a", "agent", Map.of("agentId", "")),
                                node("k", "knowledge", Map.of("collectionId", "")),
                                node("ok", "approval", Map.of()),
                                node("w1", "output", Map.of("channel", "whatsapp")),
                                node("w2", "output", Map.of("channel", "whatsapp")),
                                node("log", "output", Map.of("channel", "log"))),
                        List.of());

        Requirements req =
                WorkflowGeneratorSupport.requirements(
                        graph, List.of(), List.of(), Boolean.FALSE, Boolean.FALSE);

        List<String> kinds = req.items().stream().map(RequirementItem::kind).toList();
        assertEquals(
                List.of("agent", "knowledge", "approval-policy", "channel"),
                kinds,
                "one WhatsApp hint, not one per node");
        RequirementItem channel = req.items().get(3);
        assertEquals("/admin/channels", channel.href());
        assertEquals("w1", channel.nodeId());
        assertEquals("/admin/approvals", req.items().get(2).href());
        assertEquals("a", req.items().get(0).nodeId());

        Requirements unknown =
                WorkflowGeneratorSupport.requirements(graph, List.of(), List.of(), null, null);
        assertFalse(
                unknown.items().stream()
                        .anyMatch(
                                i ->
                                        "channel".equals(i.kind())
                                                || "approval-policy".equals(i.kind())),
                "unknown workspace facts never produce a hint");
        Requirements fine =
                WorkflowGeneratorSupport.requirements(
                        graph, List.of(), List.of(), Boolean.TRUE, Boolean.TRUE);
        assertEquals(2, fine.items().size());
    }

    @Test
    void anEmptyCanvasIsNotAnEdit() {
        assertFalse(WorkflowGeneratorSupport.isEditable(null));
        assertFalse(
                WorkflowGeneratorSupport.isEditable(new WorkflowGraphDto(List.of(), List.of())));
        assertTrue(
                WorkflowGeneratorSupport.isEditable(
                        new WorkflowGraphDto(List.of(node("t", "trigger", Map.of())), List.of())));
    }
}
