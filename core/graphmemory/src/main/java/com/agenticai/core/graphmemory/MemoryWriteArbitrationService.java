package com.agenticai.core.graphmemory;

import com.agenticai.core.chat.structured.StructuredOutput;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Master Roadmap 2026 item #11 ("Memory write arbitration", Mem0-style) - an LLM-arbitrated
 * add/update/merge/discard decision for each candidate graph triple BEFORE it gets written,
 * instead of {@link GraphMemoryService#remember} unconditionally {@code MERGE}-ing every
 * extracted triple in. See that class's javadoc for why plain {@code MERGE} alone is not enough:
 * it dedupes an exact-repeat fact but has no notion of "this new fact contradicts/supersedes an
 * old one" - {@code (Priya, MANAGER_OF_BY, Raj)} followed later by {@code (Priya, MANAGER_OF_BY,
 * Rahul)} just becomes two edges out of Priya, not a correction.
 *
 * <p><b>Deliberately NOT a {@code @Profile("graph-memory")} bean</b> (unlike {@link
 * GraphMemoryService} itself): this is stateless decision logic with no direct AGE/JDBC
 * dependency of its own - {@link GraphMemoryService} is always passed in as a method parameter,
 * the same "resolved once by the caller, threaded through as a plain argument" shape {@code
 * ChatClient}/{@link EntityExtractionService} already use for {@code RememberFactToolCallback}
 * (see {@code AgentToolProvider#resolveMemoryTools}). Making this bean itself profile-conditional
 * would force every caller back into {@code ObjectProvider} null-checking for zero benefit, since
 * it already no-ops correctly whenever {@code graphMemoryService} is null.
 *
 * <p><b>{@code app.graph-memory.arbitration.enabled}, default {@code false}</b>: off is a real,
 * behavior-preserving no-op - {@link #arbitrateAndWrite} falls straight through to the
 * unconditional {@link GraphMemoryService#remember} call, exactly the "off is a true no-op"
 * contract {@code code-intel.enabled}/{@code CODE_INTEL_ENABLED} already established. This adds
 * one extra LLM call per {@code remember_fact} invocation once turned on - a real latency/cost
 * change to already-live behavior (graph-memory itself defaults ON via the {@code graph-memory}
 * Spring profile), so it stays opt-in rather than replacing the default write path outright,
 * even though the roadmap item frames arbitration as the eventual intended default.
 *
 * <p><b>Failure contract (verified by {@code MemoryWriteArbitrationServiceTest}, the
 * LongMemEval-lite harness added 2026-10-02):</b> a memory is
 * never lost to the arbitration layer. Blank / malformed / partial / unknown-verdict LLM output
 * resolves to {@code ADD} per candidate; a thrown LLM or recall call falls back to the plain
 * unconditional write; and a {@code DISCARD} verdict is only honoured when an already-known fact
 * with the same subject, relation AND (whitespace/case-normalised) object was actually shown to
 * the model (otherwise a hallucinated "redundant" verdict would silently drop a brand-new fact -
 * e.g. (Sam, LIKES, coffee) next to a known (Sam, LIKES, tea) - so it is downgraded to {@code ADD}).
 * Known limitation: two candidates in ONE batch sharing (subject, relation) with different
 * objects, both judged UPDATE, supersede each other (last wins); multi-valued relations should
 * be judged ADD by the model.
 *
 * <p><b>Metrics</b> (only when a {@link MeterRegistry} bean exists):
 * {@code graph.memory.arbitration.decisions} tagged {@code decision=ADD|UPDATE|MERGE|DISCARD}
 * (what was actually applied, after guards; counted only once every write of the batch has
 * succeeded, so the exception fallback path records no decisions) and {@code graph.memory.arbitration.fallbacks}
 * tagged {@code reason=blank_response|parse_failure|exception|discard_unverified}.
 *
 * <p><b>Checklist before flipping {@code app.graph-memory.arbitration.enabled} to true by
 * default</b> (state 2026-10-05; evidence and numbers in
 * {@code docs/plans/MEMORY-ARBITRATION-FLIP-REPORT.md}). The flag default is unchanged (false).
 * <ol>
 *   <li>DONE - {@code supersede} on real AGE 1.6.0 / PostgreSQL 16 ({@code GraphMemoryRealAgeTest},
 *       run with {@code MEMEVAL_PG_URL}): the old edge gets {@code valid_to}, is hidden from {@code
 *       recallRelated} and still answers {@code recallRelatedAt}; add/update/merge/discard/
 *       contradiction, tenant isolation and idempotency pass. It found and fixed three defects the
 *       mocks hid (see {@link GraphMemoryService}'s class javadoc): the missing {@code ag_catalog}
 *       search_path, the unsupported {@code all()} list predicate, and closed edges being re-matched
 *       or re-stamped by {@code MERGE}/supersede. Before this fix NO graph write or recall worked on
 *       real AGE at all, so this flag was the least of it.</li>
 *   <li>OPEN, BLOCKING - the arbitration prompt never says that some relations are multi-valued
 *       (speaks, likes, has_pet, visited...). Taken literally ("same subject and relation but a
 *       DIFFERENT object ... supersedes it") it makes a faithful model UPDATE one language over
 *       another. {@code MemoryEval} ("LongMemEval-lite (own fixtures)") shows the cost of exactly
 *       that: multi_session_aggregation accuracy falls from 100% to 11% with a naive arbitrator, even
 *       though knowledge_update rises from 0% to 100%. Fix the prompt (multi-valued relations are
 *       ADD; only a changed single-valued attribute is UPDATE) and re-run the eval below with a
 *       REAL model before turning the flag on anywhere.</li>
 *   <li>OPEN - replay the cases in {@code arbitration/arbitration-cases.json} and the {@code
 *       MemoryEval} fixture through the real chat model ({@code MemoryEvalRunner} can point at a
 *       live tenant; its arbitrator is scripted today, wire the real {@link ChatClient} in) and
 *       compare the {@code decisions} counter mix with the fixture expectations; investigate any
 *       ADD-heavy, UPDATE-heavy or DISCARD-heavy drift. No real LLM decision has been verified.</li>
 *   <li>OPEN - watch {@code fallbacks} in staging: parse_failure/exception above ~2% of calls means
 *       the prompt or model is unsuitable.</li>
 *   <li>OPEN - measure the added latency of the extra LLM call per {@code remember_fact} and confirm
 *       it is acceptable on the tool-call path (or move the call off the hot path). The storage side
 *       now costs 3-4 round trips per write plus 3 for search_path handling (about 17 ms median on a
 *       local container, see the report); the LLM call is not measured.</li>
 *   <li>DONE - per-tenant schema routing: two tenants writing the same agent+subject land in their
 *       own {@code <schema>_kg} graphs and erasure in one leaves the other intact (real AGE).</li>
 *   <li>OPEN - single replica only: the open-edge check + vertex MERGE + edge CREATE is not atomic,
 *       so two concurrent writers of the same fact can create a duplicate open edge. Not tested
 *       multi-replica and not load-tested; recall is a sequential scan (no property index), about
 *       37 ms at 12k vertices on the local container, growing linearly.</li>
 *   <li>OPEN - canary one tenant for a week; spot-check that DISCARDed facts really were redundant
 *       and that no multi-valued fact was superseded.</li>
 * </ol>
*/
@Service
public class MemoryWriteArbitrationService {

    private static final Logger log = LoggerFactory.getLogger(MemoryWriteArbitrationService.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final Set<String> VALID_DECISIONS = Set.of("ADD", "UPDATE", "MERGE", "DISCARD");

    // How many existing-fact sentences get fed into the arbitration prompt as context, across all
    // subjects in one candidate batch - the same defensive-cap posture GraphMemoryService.
    // recallRelated's own "LIMIT 20" already uses, so one remember_fact call with a lot of
    // candidate triples can never blow up the arbitration prompt unboundedly.
    private static final int MAX_CONTEXT_FACTS = 30;

    private static final String ARBITRATION_SYSTEM_PROMPT = """
        You are deciding how to write new candidate facts into a long-term memory graph, given the
        facts already known about the same subjects. For EACH candidate fact, decide exactly one
        action:

        - "ADD": a genuinely new fact - no existing fact covers the same (subject, relation) pair.
        - "UPDATE": an existing fact has the same subject and relation but a DIFFERENT object - the
          candidate supersedes it (e.g. a changed preference, a changed manager, a changed shop
          location). The old fact should be replaced, not kept alongside the new one.
        - "MERGE": the candidate restates or elaborates an existing fact about the same (subject,
          relation) with an equivalent or compatible object - treat this the same as UPDATE (the
          newer, more complete wording replaces the older one).
        - "DISCARD": the candidate is redundant with an existing fact - same subject, relation, AND
          an equivalent object, or a trivial rewording with no new information.

        Respond with ONLY a JSON array, one entry per candidate fact, in the same order given, in
        this exact shape - no other text, no markdown fence:
        [{"subject": "...", "relation": "...", "object": "...", "decision": "ADD"|"UPDATE"|"MERGE"|"DISCARD"}]
        """;

    @Value("${app.graph-memory.arbitration.enabled:false}")
    private boolean enabled;

    private MeterRegistry meterRegistry;

    @Autowired(required = false)
    void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** Test seam - the flag is a {@code @Value} field in production. */
    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private void count(String name, String tagKey, String tagValue) {
        try {
            if (meterRegistry != null) {
                meterRegistry.counter("graph.memory.arbitration." + name, tagKey, tagValue).increment();
            }
        } catch (RuntimeException ignored) {
            // metrics must never affect a memory write
        }
    }

    /**
     * Writes {@code candidateTriples} for this agent+subject, arbitrating each one against
     * already-known facts first when {@code app.graph-memory.arbitration.enabled=true} and a
     * chat context is available. Falls through to {@link GraphMemoryService#remember}'s plain
     * unconditional write (today's exact behavior) whenever arbitration is off, no {@code
     * chatClient} is available, or the arbitration call itself fails for any reason - never
     * throws, and never leaves a candidate fact unwritten due to an arbitration-layer error.
     */
    public void arbitrateAndWrite(
        ChatClient chatClient,
        GraphMemoryService graphMemoryService,
        UUID agentId,
        String subjectKey,
        List<GraphTriple> candidateTriples
    ) {
        if (graphMemoryService == null || candidateTriples == null || candidateTriples.isEmpty()) {
            return;
        }
        if (!enabled || chatClient == null) {
            graphMemoryService.remember(agentId, subjectKey, candidateTriples);
            return;
        }
        try {
            List<ArbitrationDecision> decisions = decide(chatClient, graphMemoryService, agentId, subjectKey, candidateTriples);
            applyDecisions(graphMemoryService, agentId, subjectKey, candidateTriples, decisions);
        } catch (Exception ex) {
            log.warn(
                "Memory write arbitration failed for agent {} subject '{}' - falling back to unconditional write for {} triple(s)",
                agentId, subjectKey, candidateTriples.size(), ex
            );
            count("fallbacks", "reason", "exception");
            graphMemoryService.remember(agentId, subjectKey, candidateTriples);
        }
    }

    private List<ArbitrationDecision> decide(
        ChatClient chatClient, GraphMemoryService graphMemoryService, UUID agentId, String subjectKey, List<GraphTriple> candidateTriples
    ) {
        List<GraphFact> existingFacts = collectExistingFacts(graphMemoryService, agentId, subjectKey, candidateTriples);
        String existingFactsBlock = existingFacts.stream()
            .map(f -> "- " + f.asSentence())
            .collect(Collectors.joining("\n"));
        String candidatesBlock = buildCandidatesBlock(candidateTriples);

        String userPrompt = (existingFactsBlock.isBlank()
            ? "Already-known facts about these subjects: (none yet)\n\n"
            : "Already-known facts about these subjects:\n" + existingFactsBlock + "\n\n")
            + "Candidate facts to decide on:\n" + candidatesBlock;

        String response = chatClient.prompt()
            .system(ARBITRATION_SYSTEM_PROMPT)
            .user(userPrompt)
            .call()
            .content();

        if (response == null || response.isBlank()) {
            // No parseable verdict at all - fail open to ADD for every candidate, i.e. exactly
            // today's unconditional-write behavior, rather than silently dropping facts.
            count("fallbacks", "reason", "blank_response");
            return candidateTriples.stream().map(t -> new ArbitrationDecision(t.subject(), t.relation(), t.object(), "ADD")).toList();
        }
        return parseDecisions(response.trim(), candidateTriples, existingFacts);
    }

    /**
     * One {@link GraphMemoryService#recallRelated} lookup per distinct candidate subject
     * (1-hop, mirroring {@code GraphRecallToolCallback}'s own default hop count), deduplicated and
     * capped at {@link #MAX_CONTEXT_FACTS} total facts.
     */
    private List<GraphFact> collectExistingFacts(
        GraphMemoryService graphMemoryService, UUID agentId, String subjectKey, List<GraphTriple> candidateTriples
    ) {
        Set<String> subjectsSeen = new LinkedHashSet<>();
        Set<String> sentences = new LinkedHashSet<>();
        List<GraphFact> facts = new ArrayList<>();
        for (GraphTriple triple : candidateTriples) {
            if (facts.size() >= MAX_CONTEXT_FACTS) {
                break;
            }
            String subject = triple.subject();
            if (subject == null || subject.isBlank() || !subjectsSeen.add(subject.toLowerCase(Locale.ROOT))) {
                continue;
            }
            List<GraphFact> recalled = graphMemoryService.recallRelated(agentId, subjectKey, subject, 1);
            if (recalled == null) {
                continue;
            }
            for (GraphFact fact : recalled) {
                if (fact != null && fact.subject() != null && fact.relation() != null && fact.object() != null
                    && sentences.add(fact.asSentence())) {
                    facts.add(fact);
                }
                if (facts.size() >= MAX_CONTEXT_FACTS) {
                    break;
                }
            }
        }
        return facts;
    }

    private String buildCandidatesBlock(List<GraphTriple> candidateTriples) {
        StringBuilder sb = new StringBuilder();
        for (GraphTriple triple : candidateTriples) {
            sb.append("- (").append(triple.subject()).append(", ").append(triple.relation()).append(", ")
                .append(triple.object()).append(")\n");
        }
        return sb.toString();
    }

    /**
     * Same defensive JSON-array parsing discipline as {@code EntityExtractionService.
     * parseTriples} (markdown-fence stripping, per-node null-safe field extraction, catch-all
     * fail-open) - the one difference is what "fail open" resolves to here: every candidate not
     * matched to a recognized decision defaults to {@code ADD}, so a parse failure degrades to
     * today's unconditional-write behavior rather than silently discarding facts.
     */
    private List<ArbitrationDecision> parseDecisions(
        String jsonResponse, List<GraphTriple> candidateTriples, List<GraphFact> existingFacts
    ) {
        Map<String, String> decisionByKey = new LinkedHashMap<>();
        try {
            JsonNode array = StructuredOutput.readArray(MAPPER, jsonResponse).orElse(null);
            if (array == null) {
                count("fallbacks", "reason", "parse_failure");
                // Size only: the response is LLM output derived from the user's chat (JAVA-033).
                log.warn("Could not parse memory write arbitration response as JSON - defaulting every candidate to ADD ({} chars)",
                    jsonResponse == null ? 0 : jsonResponse.length());
            } else {
                for (JsonNode node : array) {
                    String subject = textOrNull(node, "subject");
                    String relation = textOrNull(node, "relation");
                    String object = textOrNull(node, "object");
                    String decision = textOrNull(node, "decision");
                    if (subject != null && relation != null && object != null && decision != null) {
                        decisionByKey.put(key(subject, relation, object), decision.toUpperCase(Locale.ROOT));
                    }
                }
            }
        } catch (Exception ex) {
            decisionByKey.clear();
            count("fallbacks", "reason", "parse_failure");
            log.warn("Could not parse memory write arbitration response as JSON - defaulting every candidate to ADD ({} chars)",
                jsonResponse == null ? 0 : jsonResponse.length());
        }

        List<ArbitrationDecision> decisions = new ArrayList<>();
        for (GraphTriple triple : candidateTriples) {
            String decision = decisionByKey.getOrDefault(key(triple.subject(), triple.relation(), triple.object()), "ADD");
            if (!VALID_DECISIONS.contains(decision)) {
                decision = "ADD";
            }
            if ("DISCARD".equals(decision) && !hasKnownFact(existingFacts, triple)) {
                // Never lose a memory to a hallucinated "redundant" verdict: no known fact with
                // this (subject, relation, object) was shown to the model, so there is nothing
                // it could be redundant with.
                count("fallbacks", "reason", "discard_unverified");
                decision = "ADD";
            }
            decisions.add(new ArbitrationDecision(triple.subject(), triple.relation(), triple.object(), decision));
        }
        return decisions;
    }

    private static boolean hasKnownFact(List<GraphFact> existingFacts, GraphTriple triple) {
        if (triple.subject() == null || triple.relation() == null || triple.object() == null) {
            return false;
        }
        String subject = triple.subject().trim();
        String relation = triple.relation().trim();
        String object = normalise(triple.object());
        for (GraphFact fact : existingFacts) {
            if (fact.subject().trim().equalsIgnoreCase(subject)
                && fact.relation().trim().equalsIgnoreCase(relation)
                && normalise(fact.object()).equals(object)) {
                return true;
            }
        }
        return false;
    }

    private static String normalise(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private void applyDecisions(
        GraphMemoryService graphMemoryService, UUID agentId, String subjectKey,
        List<GraphTriple> candidateTriples, List<ArbitrationDecision> decisions
    ) {
        List<GraphTriple> toAdd = new ArrayList<>();
        int discarded = 0;
        List<String> applied = new ArrayList<>();
        for (int i = 0; i < candidateTriples.size(); i++) {
            GraphTriple triple = candidateTriples.get(i);
            String decision = i < decisions.size() ? decisions.get(i).decision() : "ADD";
            switch (decision) {
                case "DISCARD" -> discarded++;
                case "UPDATE", "MERGE" -> graphMemoryService.supersede(agentId, subjectKey, triple);
                default -> {
                    decision = "ADD";
                    toAdd.add(triple);
                }
            }
            applied.add(decision);
        }
        if (!toAdd.isEmpty()) {
            graphMemoryService.remember(agentId, subjectKey, toAdd);
        }
        // Only reached when every write succeeded - a throw above falls back to the plain write
        // without having recorded decisions that were never (fully) applied.
        applied.forEach(d -> count("decisions", "decision", d));
        if (discarded > 0) {
            log.debug(
                "Memory write arbitration discarded {} redundant candidate fact(s) for agent {} subject '{}'",
                discarded, agentId, subjectKey
            );
        }
    }

    private String key(String subject, String relation, String object) {
        return subject.toLowerCase(Locale.ROOT).trim() + "|" + relation.toLowerCase(Locale.ROOT).trim()
            + "|" + object.toLowerCase(Locale.ROOT).trim();
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull() || value.asText().isBlank()) ? null : value.asText().trim();
    }

    private record ArbitrationDecision(String subject, String relation, String object, String decision) {
    }
}
