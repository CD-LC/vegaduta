package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.prompts.PromptDefinition;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptRiskClass;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every secondary-model-call prompt (summarizers, quality passes, judges, routers, memory extraction,
 * learning, RAG rewrite, workflow node wrappers, the escalation router's word lists) is a registry key
 * with a description, so {@code GET /api/prompts} lists it, and with exactly the placeholders its call
 * site fills. The wording itself is pinned by the golden test of the module that owns each key.
 */
class SecondaryCallPromptCatalogTest {

    /** key -> placeholders the call site supplies. */
    private static final Map<String, Set<String>> KEYS =
            Map.ofEntries(
                    Map.entry("summarizer.compaction", Set.of()),
                    Map.entry("summarizer.session", Set.of()),
                    Map.entry("quality.reflexion.critique", Set.of()),
                    Map.entry("quality.reflexion.revise", Set.of()),
                    Map.entry("quality.consistency.check", Set.of()),
                    Map.entry("quality.consistency.revise", Set.of()),
                    Map.entry("planner.task", Set.of()),
                    Map.entry("planner.task.replan", Set.of()),
                    Map.entry("planner.task.tools_none", Set.of()),
                    Map.entry("planner.task.tools", Set.of("tools")),
                    Map.entry("judge.quality", Set.of()),
                    Map.entry("router.complexity", Set.of()),
                    Map.entry("router.agent_select", Set.of()),
                    Map.entry("router.agent_additional", Set.of("agent")),
                    Map.entry("skills.distilled.purpose", Set.of("runs", "sessions")),
                    Map.entry("skills.distilled.when_to_use", Set.of()),
                    Map.entry("skills.distilled.procedure", Set.of()),
                    Map.entry("skills.distilled.footer", Set.of()),
                    Map.entry("skills.distilled.description", Set.of("runs", "sequence")),
                    Map.entry("detector.replay.read_only_prefixes", Set.of()),
                    Map.entry("detector.replay.mutating_words", Set.of()),
                    Map.entry("detector.replay.never_replay_tools", Set.of()),
                    Map.entry("detector.escalation.low_confidence_markers", Set.of()),
                    Map.entry("swarm.verifier", Set.of()),
                    Map.entry("swarm.brief", Set.of("objective", "subtask")),
                    Map.entry("swarm.brief.objective", Set.of("goal")),
                    Map.entry("memory.entity_extraction", Set.of()),
                    Map.entry("memory.arbitration", Set.of()),
                    Map.entry("learning.lesson", Set.of()),
                    Map.entry("learning.skill_draft", Set.of()),
                    Map.entry("learning.skill_self_check", Set.of()),
                    Map.entry("research.system", Set.of()),
                    Map.entry("rag.multi_query", Set.of()),
                    Map.entry("rag.hyde", Set.of()),
                    Map.entry("eval.judge.case", Set.of()),
                    Map.entry("eval.judge.helpfulness", Set.of("question", "response", "system_prompt")),
                    Map.entry("eval.judge.relevance", Set.of("question", "response")),
                    Map.entry("eval.judge.safety", Set.of("system_prompt", "question", "response")),
                    Map.entry("eval.judge.tone", Set.of("system_prompt", "response")),
                    Map.entry("eval.judge.faithfulness", Set.of("question", "context", "answer")),
                    Map.entry("eval.judge.accuracy", Set.of("question", "response", "reference_block", "reference_check")),
                    Map.entry("eval.judge.accuracy.reference", Set.of("reference")),
                    Map.entry("eval.judge.accuracy.reference_check", Set.of()),
                    Map.entry("lab.judge.score", Set.of()),
                    Map.entry("lab.judge.same", Set.of()),
                    Map.entry("workflow.ai_classifier", Set.of()),
                    Map.entry("workflow.ai_extractor", Set.of()),
                    Map.entry("workflow.ai_extractor.repair", Set.of("issues")),
                    Map.entry("workflow.supervisor.route", Set.of("routes", "instructions", "input")),
                    Map.entry("workflow.supervisor.instructions", Set.of("instructions")),
                    Map.entry("workflow.params_inference", Set.of()),
                    Map.entry("workflow.edit_redaction_note", Set.of()));

    @Test
    void everySecondaryCallKeyIsRegisteredWithADescriptionAndItsPlaceholders() {
        var catalog = PromptResolver.defaultsOnly().catalog();

        assertThat(catalog.problems()).isEmpty();
        KEYS.forEach(
                (key, placeholders) -> {
                    PromptDefinition def = catalog.find(key).orElseThrow(() -> new AssertionError("not registered: " + key));
                    assertThat(def.title()).as(key).isNotBlank();
                    assertThat(def.description()).as(key).isNotBlank();
                    assertThat(def.riskClass()).as(key).isEqualTo(PromptRiskClass.SECONDARY_CALL);
                    assertThat(def.disableable()).as(key + " cannot run without its text").isFalse();
                    assertThat(def.retired()).as(key).isFalse();
                    assertThat(def.placeholderNames()).as(key).isEqualTo(placeholders);
                    assertThat(catalog.defaultText(key)).as(key).isNotBlank();
                });
    }

    @Test
    void theTextsThatGuardAgainstLeaksAndReplayNeedAcknowledgementToChange() {
        var catalog = PromptResolver.defaultsOnly().catalog();
        for (String key :
                Set.of(
                        "detector.replay.read_only_prefixes",
                        "detector.replay.mutating_words",
                        "detector.replay.never_replay_tools",
                        "swarm.verifier",
                        "learning.lesson",
                        "learning.skill_draft",
                        "research.system",
                        "workflow.ai_classifier",
                        "workflow.ai_extractor",
                        "workflow.edit_redaction_note")) {
            assertThat(catalog.find(key).orElseThrow().securityCritical()).as(key).isTrue();
        }
    }
}
