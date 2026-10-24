package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.turn.VerifierEscalationRouter.Reason;
import com.agenticai.core.agents.turn.VerifierEscalationRouter.Signals;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class VerifierEscalationRouterTest {

    private static VerifierEscalationRouter on(SimpleMeterRegistry reg) {
        return new VerifierEscalationRouter(reg, true, false, 2, 0.2);
    }

    @Test
    void flagOffIsInert() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        VerifierEscalationRouter r = new VerifierEscalationRouter(reg, false, false, 2, 0.2);
        ChatClient cheap = mock(ChatClient.class);
        UUID agent = UUID.randomUUID();
        r.registerCheap(agent, cheap, () -> mock(ChatClient.class));
        assertFalse(r.isActive());
        assertTrue(r.escalationFor(agent, cheap).isEmpty());
        assertTrue(r.shouldEscalate(new Signals(true, true, true, 9, true)).isEmpty());
        r.recordDecision(agent, Optional.of(Reason.EMPTY_REPLY), 100);
        assertTrue(reg.getMeters().isEmpty());
    }

    @Test
    void killSwitchWinsOverEnabled() {
        VerifierEscalationRouter r = new VerifierEscalationRouter(new SimpleMeterRegistry(), true, true, 2, 0.2);
        assertFalse(r.isActive());
        assertTrue(r.shouldEscalate(new Signals(false, false, false, 0, true)).isEmpty());
    }

    @Test
    void registrationMatchesOnlyTheCheapClientOfThatAgent() {
        VerifierEscalationRouter r = on(new SimpleMeterRegistry());
        ChatClient cheap = mock(ChatClient.class);
        ChatClient other = mock(ChatClient.class);
        ChatClient strong = mock(ChatClient.class);
        UUID agent = UUID.randomUUID();
        r.registerCheap(agent, cheap, () -> strong);
        assertTrue(r.escalationFor(agent, cheap).isPresent());
        assertEquals(strong, r.escalationFor(agent, cheap).get().get());
        assertTrue(r.escalationFor(agent, other).isEmpty());
        assertTrue(r.escalationFor(UUID.randomUUID(), cheap).isEmpty());
    }

    @Test
    void signalsDetected() {
        VerifierEscalationRouter r = on(new SimpleMeterRegistry());
        assertTrue(r.inspect("", null, 0).emptyReply());
        assertTrue(r.inspect("plain prose", "{\"type\":\"object\"}", 0).parseFailure());
        assertFalse(r.inspect("{\"a\":1}", "{\"type\":\"object\"}", 0).parseFailure());
        assertFalse(r.inspect("```json\n{\"a\":1}\n```", "{\"type\":\"object\"}", 0).parseFailure());
        assertFalse(r.inspect("prose is fine without a schema", null, 0).parseFailure());
        assertTrue(r.inspect("Honestly, I'm not sure about that.", null, 0).lowConfidence());
        assertFalse(r.inspect("The capital is Paris.", null, 0).lowConfidence());
    }

    @Test
    void escalationPriorityAndToolErrorThreshold() {
        VerifierEscalationRouter r = on(new SimpleMeterRegistry());
        assertEquals(Optional.of(Reason.TOOL_ERROR_LOOP), r.shouldEscalate(new Signals(false, false, false, 2, false)));
        assertTrue(r.shouldEscalate(new Signals(false, false, false, 1, false)).isEmpty());
        assertEquals(Optional.of(Reason.PARSE_FAILURE), r.shouldEscalate(new Signals(true, false, true, 5, false)));
        assertEquals(Optional.of(Reason.GUARDRAIL_FLAG), r.shouldEscalate(new Signals(false, true, true, 0, false)));
        assertEquals(Optional.of(Reason.LOW_CONFIDENCE), r.shouldEscalate(new Signals(false, false, true, 0, false)));
        assertTrue(r.shouldEscalate(Signals.none()).isEmpty());
    }

    @Test
    void customMarkersReplaceDefaults() {
        // app.chat.escalation.low-confidence-markers is a deprecated alias for the platform default of the key
        VerifierEscalationRouter r = new VerifierEscalationRouter(new SimpleMeterRegistry(), true, false, 2, 0.2);
        r.setPromptResolver(new com.agenticai.core.prompts.DefaultPromptResolver(
            com.agenticai.core.prompts.PromptResolver.defaultsOnly().catalog(), null,
            new com.agenticai.core.prompts.PromptProperties(),
            java.util.List.of(PromptPropertyAliases.lowConfidenceMarkers("maybe, perhaps"))));
        assertTrue(r.inspect("Perhaps.", null, 0).lowConfidence());
        assertFalse(r.inspect("I'm not sure", null, 0).lowConfidence());
    }

    @Test
    void decisionsAreCountedWithEstimatedSavings() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        VerifierEscalationRouter r = on(reg);
        UUID agent = UUID.randomUUID();
        r.recordDecision(agent, Optional.empty(), 400); // 100 tokens kept cheap -> 80 saved
        r.recordDecision(agent, Optional.of(Reason.LOW_CONFIDENCE), 400); // 100 tokens -> 20 wasted
        assertEquals(1.0, reg.get(VerifierEscalationRouter.DECISION_METRIC)
            .tags("outcome", "kept_cheap", "reason", "none").counter().count());
        assertEquals(1.0, reg.get(VerifierEscalationRouter.DECISION_METRIC)
            .tags("outcome", "escalated", "reason", "low_confidence").counter().count());
        assertEquals(80.0, reg.get(VerifierEscalationRouter.SAVED_METRIC).counter().count(), 1e-9);
        assertEquals(20.0, reg.get(VerifierEscalationRouter.WASTED_METRIC).counter().count(), 1e-9);
    }

    private static Path findGoldenDataset() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            for (String rel : new String[] {"quality/src/main/resources/golden-dataset.json",
                "core/quality/src/main/resources/golden-dataset.json"}) {
                Path candidate = dir.resolve(rel);
                if (Files.exists(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    @Test
    void lowConfidenceMarkerInsideALongAnswerIsNotAFalsePositive() {
        VerifierEscalationRouter r = on(new SimpleMeterRegistry());
        String body = "The migration has three phases and each one is described in detail below. ".repeat(12);
        // marker buried deep in a long, substantive answer: not low confidence
        assertFalse(r.inspect(body + "Some teams say I don't know where to start, but here is a plan.", null, 0).lowConfidence());
        // marker at the opening of a LONG (>= 400 chars) answer: substantive, stays cheap
        assertFalse(r.inspect("I'm not sure, but here is my best attempt. " + body, null, 0).lowConfidence());
        // a short reply is only low confidence when the marker is in its opening window
        assertTrue(r.inspect("I'm not sure, but here is my best attempt. " + "x".repeat(200), null, 0).lowConfidence());
        assertFalse(r.inspect("y".repeat(250) + " I don't know.", null, 0).lowConfidence());
        // a reply built on tool results is reporting what the tool said: never low confidence
        assertFalse(r.inspect("I don't know.", null, 0, 1).lowConfidence());
        assertTrue(r.inspect("I don't know.", null, 0, 0).lowConfidence());
        // short hedge anywhere: low confidence
        assertTrue(r.inspect("Paris, though I don't know for certain.", null, 0).lowConfidence());
        // ordinary answers that merely mention uncertainty vocabulary stay cheap
        assertFalse(r.inspect(body, null, 0).lowConfidence());
    }

    @Test
    void replaySafeOnlyForReadOnlyToolNames() {
        VerifierEscalationRouter r = on(new SimpleMeterRegistry());
        assertTrue(r.replaySafe(java.util.List.of()));
        assertTrue(r.replaySafe(java.util.List.of("search_flights", "get_calendar", "list_events")));
        assertFalse(r.replaySafe(java.util.List.of("search_flights", "send_email")));
        assertFalse(r.replaySafe(java.util.List.of("create_invoice")));
        assertFalse(r.replaySafe(java.util.List.of("unknown_tool")));
    }

    @Test
    void browserClickIsNotReadOnlyByNameFallback() {
        VerifierEscalationRouter r = on(new SimpleMeterRegistry());
        assertFalse(r.replaySafe(java.util.List.of("browser_click")));
        assertFalse(r.replaySafe(java.util.List.of("browser_type")));
        assertTrue(r.replaySafe(java.util.List.of("browse_page")));
        assertFalse(r.replaySafe(java.util.List.of("getaway_booking")));
        // an unclassified MCP tool with a read verb but a mutating word is not replay-safe
        assertFalse(r.replaySafe(java.util.List.of("get_and_delete")));
        assertFalse(r.replaySafe(java.util.List.of("mcp__crm__list_and_send_digest")));
        assertTrue(r.replaySafe(java.util.List.of("get_status", "search_documents")));
    }

    @Test
    void classificationOverridesTheNameHeuristic() {
        VerifierEscalationRouter r = on(new SimpleMeterRegistry());
        r.useClassification(name -> switch (name) {
            case "get_report" -> Optional.of(false);      // looks read-only, classified durable
            case "do_thing" -> Optional.of(true);         // looks mutating, classified replay-safe
            default -> Optional.empty();
        });
        assertFalse(r.replaySafe(java.util.List.of("get_report")));
        assertTrue(r.replaySafe(java.util.List.of("do_thing")));
        // unclassified falls back to the name heuristic
        assertTrue(r.replaySafe(java.util.List.of("list_events")));
        assertFalse(r.replaySafe(java.util.List.of("send_email")));
        // a failing lookup fails safe to the name heuristic, never throws
        r.useClassification(name -> {
            throw new IllegalStateException("db down");
        });
        assertFalse(r.replaySafe(java.util.List.of("send_email")));
        assertTrue(r.replaySafe(java.util.List.of("get_x")));
    }

    @Test
    void httpRequestIsNeverReplaySafeEvenIfClassifiedNonDurable() {
        VerifierEscalationRouter r = on(new SimpleMeterRegistry());
        r.useClassification(name -> Optional.of(true));
        assertFalse(r.replaySafe(java.util.List.of("http_request")));
    }

    @Test
    void skippedEscalationIsCountedAndInertWhenOff() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        VerifierEscalationRouter r = on(reg);
        r.recordSkippedForSideEffects(UUID.randomUUID(), Reason.LOW_CONFIDENCE);
        assertEquals(1.0, reg.get(VerifierEscalationRouter.SKIPPED_METRIC).tags("reason", "low_confidence").counter().count());
        SimpleMeterRegistry offReg = new SimpleMeterRegistry();
        new VerifierEscalationRouter(offReg, false, false, 2, 0.2)
            .recordSkippedForSideEffects(UUID.randomUUID(), Reason.LOW_CONFIDENCE);
        assertTrue(offReg.getMeters().isEmpty());
    }

    /**
     * Offline harness over core/quality's golden dataset (fixtures only; no model, no
     * network). A scripted "cheap model" answers each case; the router must escalate exactly
     * the cases whose scripted answer is unsafe and keep the rest, and savings must be
     * recorded for the kept ones.
     */
    @Test
    void offlineHarnessOverGoldenDatasetFixtures() throws Exception {
        Path fixture = findGoldenDataset();
        // Walks up from the working directory (module dir under Maven, repo root under an IDE), so a
        // different cwd no longer skips this silently. Skips only if the fixture is truly absent.
        Assumptions.assumeTrue(fixture != null, "eval fixture golden-dataset.json not found above " + Path.of("").toAbsolutePath());
        JsonNode cases = new ObjectMapper().readTree(Files.readString(fixture));
        assertTrue(cases.size() > 10);

        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        VerifierEscalationRouter r = on(reg);
        int expectedEscalations = 0;
        int escalations = 0;
        int kept = 0;
        int i = 0;
        for (JsonNode c : cases) {
            String category = c.path("category").asText("");
            // Scripted cheap-model behaviour: reasoning-heavy categories hedge, every 7th case
            // returns nothing, everything else answers confidently.
            String answer;
            if (i % 7 == 0) {
                answer = "";
            } else if (category.equals("tool-judgment") || category.equals("long-context")) {
                answer = "I'm not sure how to handle this.";
            } else {
                answer = "Here is a confident answer.";
            }
            boolean unsafe = answer.isEmpty()
                || category.equals("tool-judgment") || category.equals("long-context");
            if (unsafe) {
                expectedEscalations++;
            }
            Optional<Reason> reason = r.shouldEscalate(r.inspect(answer, null, 0));
            assertEquals(unsafe, reason.isPresent(), "case " + c.path("id").asText());
            r.recordDecision(UUID.randomUUID(), reason, answer.length());
            if (reason.isPresent()) {
                escalations++;
            } else {
                kept++;
            }
            i++;
        }
        assertEquals(expectedEscalations, escalations);
        assertTrue(kept > 0 && escalations > 0);
        assertTrue(reg.get(VerifierEscalationRouter.SAVED_METRIC).counter().count() > 0);
        assertTrue(reg.get(VerifierEscalationRouter.WASTED_METRIC).counter().count() > 0);
    }

    // ---- schema-parse-failure trigger (wave E coverage) ----

    private static final String OBJECT_SCHEMA = "{\"type\":\"object\"}";

    @Test
    void schemaParseFailureEscalatesAndIsCountedWithItsReason() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        VerifierEscalationRouter r = on(reg);
        UUID agent = UUID.randomUUID();

        Signals s = r.inspect("Sure! Here is the report you asked for.", OBJECT_SCHEMA, 0);
        assertTrue(s.parseFailure());
        Optional<Reason> reason = r.shouldEscalate(s);
        assertEquals(Optional.of(Reason.PARSE_FAILURE), reason);

        r.recordDecision(agent, reason, 40);
        assertEquals(1.0, reg.get(VerifierEscalationRouter.DECISION_METRIC)
            .tags("outcome", "escalated", "reason", "parse_failure").counter().count());
    }

    @Test
    void parseCheckOnlyAppliesWhenTheTurnHasAnOutputSchema() {
        VerifierEscalationRouter r = on(new SimpleMeterRegistry());
        for (String schema : new String[] {null, "", "   "}) {
            assertFalse(r.inspect("plain prose, not json", schema, 0).parseFailure(), "schema=[" + schema + "]");
        }
        assertTrue(r.shouldEscalate(r.inspect("plain prose, not json", null, 0)).isEmpty());
    }

    @Test
    void parseFailureShapesTruncatedScalarAndProseWrappedJson() {
        VerifierEscalationRouter r = on(new SimpleMeterRegistry());
        assertTrue(r.inspect("{\"a\":1,", OBJECT_SCHEMA, 0).parseFailure());          // truncated
        assertTrue(r.inspect("42", OBJECT_SCHEMA, 0).parseFailure());                  // valid JSON, not object/array
        assertTrue(r.inspect("\"just a string\"", OBJECT_SCHEMA, 0).parseFailure());
        assertTrue(r.inspect("Here you go: {\"a\":1}", OBJECT_SCHEMA, 0).parseFailure()); // prose-wrapped
        assertFalse(r.inspect("[1,2,3]", OBJECT_SCHEMA, 0).parseFailure());           // arrays parse
        assertFalse(r.inspect("  {\"a\":1}\n", OBJECT_SCHEMA, 0).parseFailure());     // whitespace tolerated
        assertFalse(r.inspect("```json\n{\"a\":1}\n```", OBJECT_SCHEMA, 0).parseFailure());
    }

    @Test
    void emptyReplyOutranksParseFailureAndParseFailureOutranksLowConfidence() {
        VerifierEscalationRouter r = on(new SimpleMeterRegistry());
        // empty + schema: both signals set, EMPTY_REPLY wins
        Signals empty = r.inspect("", OBJECT_SCHEMA, 0);
        assertTrue(empty.emptyReply());
        assertEquals(Optional.of(Reason.EMPTY_REPLY), r.shouldEscalate(empty));
        // low-confidence prose against a schema: PARSE_FAILURE wins
        Signals both = r.inspect("I'm not sure about this.", OBJECT_SCHEMA, 0);
        assertTrue(both.parseFailure() && both.lowConfidence());
        assertEquals(Optional.of(Reason.PARSE_FAILURE), r.shouldEscalate(both));
    }

    @Test
    void validStructuredReplyIsKeptCheap() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        VerifierEscalationRouter r = on(reg);
        Optional<Reason> reason = r.shouldEscalate(r.inspect("{\"verdict\":\"pass\"}", OBJECT_SCHEMA, 0));
        assertTrue(reason.isEmpty());
        r.recordDecision(UUID.randomUUID(), reason, 20);
        assertEquals(1.0, reg.get(VerifierEscalationRouter.DECISION_METRIC)
            .tags("outcome", "kept_cheap", "reason", "none").counter().count());
    }

    @Test
    void parseFailureDoesNotEscalateWhenTheFlagIsOffOrKilled() {
        VerifierEscalationRouter off = new VerifierEscalationRouter(new SimpleMeterRegistry(), false, false, 2, 0.2);
        VerifierEscalationRouter killed = new VerifierEscalationRouter(new SimpleMeterRegistry(), true, true, 2, 0.2);
        for (VerifierEscalationRouter r : new VerifierEscalationRouter[] {off, killed}) {
            Signals s = r.inspect("not json", OBJECT_SCHEMA, 0);
            assertTrue(s.parseFailure()); // the verifier itself is pure ...
            assertTrue(r.shouldEscalate(s).isEmpty()); // ... but never acts while inactive
        }
    }
}
