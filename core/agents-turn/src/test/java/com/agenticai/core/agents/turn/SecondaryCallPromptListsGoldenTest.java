package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.turn.quality.TaskPlanningService;
import com.agenticai.core.prompts.PromptContext;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptSlot;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Golden tests for the secondary-call prompts that are built from several pieces or are word lists:
 * the planner tool hint, the extra-specialist question, and the replay / low-confidence lists of the
 * escalation router. Each compares the registry default with the literal or code it replaced.
 */
class SecondaryCallPromptListsGoldenTest {

    private static final List<String> OLD_READ_ONLY_PREFIXES = List.of("get", "list", "search", "read", "fetch", "find", "lookup", "look_up", "query", "retrieve", "describe", "check", "view", "recall", "load_skill", "web_search", "browse", "calculate", "count");
    private static final Set<String> OLD_MUTATING_WORDS = Set.of("delete", "remove", "send", "create", "update", "write", "post", "put", "patch", "drop", "click", "type", "submit", "cancel", "pay", "book", "execute", "exec", "run", "set", "add", "insert", "upload", "publish", "reply", "forward", "approve", "transfer", "charge", "purchase", "buy", "sell", "merge", "commit", "push", "kill", "stop", "start", "reset", "modify", "edit", "save", "store", "invoke", "call", "trigger", "email", "message", "sms", "notify");
    private static final Set<String> OLD_NEVER_REPLAY = Set.of("http_request", "http_request_write");
    private static final List<String> OLD_LOW_CONFIDENCE_MARKERS = List.of("i'm not sure", "i am not sure", "i don't know", "i do not know", "i'm not certain", "i cannot determine", "i can't determine", "low confidence", "[low-confidence]");

    private static final PromptResolver DEFAULTS = PromptResolver.defaultsOnly();

    @Test
    void replayAndMarkerListsAreTheFormerConstants() {
        PromptSlot slot = new PromptSlot();
        assertThat(slot.lines("detector.replay.read_only_prefixes", null)).containsExactlyElementsOf(OLD_READ_ONLY_PREFIXES);
        assertThat(Set.copyOf(slot.lines("detector.replay.mutating_words", null))).isEqualTo(OLD_MUTATING_WORDS);
        assertThat(slot.lines("detector.replay.mutating_words", null)).hasSize(OLD_MUTATING_WORDS.size());
        assertThat(Set.copyOf(slot.lines("detector.replay.never_replay_tools", null))).isEqualTo(OLD_NEVER_REPLAY);
        assertThat(slot.lines("detector.escalation.low_confidence_markers", null))
                .containsExactlyElementsOf(OLD_LOW_CONFIDENCE_MARKERS);
    }

    @Test
    void replayListsAreSecurityCriticalDetectors() {
        for (String key :
                List.of(
                        "detector.replay.read_only_prefixes",
                        "detector.replay.mutating_words",
                        "detector.replay.never_replay_tools")) {
            var def = DEFAULTS.catalog().find(key).orElseThrow();
            assertThat(def.securityCritical()).as(key).isTrue();
            assertThat(def.riskClass().name()).isEqualTo("SECONDARY_CALL");
        }
    }

    @Test
    void plannerToolHintEqualsTheFormerCode() {
        assertThat(OldCode.toolsHint(null)).isEqualTo("");
        assertThat(TaskPlanningService.toolsHint(null)).isEqualTo(OldCode.toolsHint(null));
        for (List<String> tools :
                List.of(List.<String>of(), List.of("web_search"), List.of("web_search", "fetch_web_page", "{agent}"))) {
            assertThat(TaskPlanningService.toolsHint(tools)).isEqualTo(OldCode.toolsHint(tools));
        }
    }

    @Test
    void extraSpecialistQuestionEqualsTheFormerFormattedLiteral() {
        String former =
                "The user's task may need more than one specialist. Would the agent \"%s\" meaningfully "
                        + "contribute to completing it? YES only if the task plausibly needs that agent's specific "
                        + "capabilities; NO if it is unrelated or only tangentially connected.";
        for (String description : List.of("Translator: translates text", "100% {agent} \\ odd")) {
            assertThat(
                            DEFAULTS.resolve("router.agent_additional", PromptContext.none().withVariable("agent", description))
                                    .textOrNull())
                    .isEqualTo(former.formatted(description));
        }
    }

    /** The former {@code TaskPlanningService.toolsHint}, verbatim. */
    private static final class OldCode {
        static String toolsHint(java.util.List<String> toolNames) {
            if (toolNames == null) {
                return "";
            }
            if (toolNames.isEmpty()) {
                return "\nThe agent has NO tools: plan only steps it can do by answering directly.\n";
            }
            return "\nThe agent's tools are: " + String.join(", ", toolNames) + ". Plan only steps these tools "
                + "or the agent's own reasoning can do. Do not add steps like \"identify the current date\" "
                + "(the date is already in the agent's prompt). If the request needs a capability none of "
                + "these tools provide, make the step say what is missing.\n";
        }
    }
}
