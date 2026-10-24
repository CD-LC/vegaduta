package com.agenticai.core.agents.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.job.AgentJobLookup;
import com.agenticai.core.agents.skills.SkillDistillationService.ToolPattern;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.rl.repo.EpisodeRepository;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The text of a distilled skill moved from Java string pieces into the prompt registry. With no
 * override the generated description and body are, byte for byte, what the former code produced
 * (copied below), and a tenant or agent override changes them for that tenant or agent only.
 */
class DistilledSkillPromptTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());
    private final SkillDistillationService service =
            new SkillDistillationService(
                    mock(AgentJobLookup.class),
                    mock(EpisodeRepository.class),
                    mock(ToolCallLogRepository.class),
                    mock(SkillService.class));

    DistilledSkillPromptTest() {
        service.setPromptResolver(resolver);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static final ToolPattern WITH_GOALS =
            new ToolPattern(
                    List.of("search_flights", "book_flight"),
                    3,
                    4,
                    0.8,
                    List.of("Book a flight to Paris", "Find a cheap fare"));
    private static final ToolPattern WITHOUT_GOALS = new ToolPattern(List.of("a", "b", "c"), 5, 7, 0.0, List.of());
    private static final ToolPattern LONG_NAMES =
            new ToolPattern(
                    List.of(
                            "a_very_long_tool_name_number_one_for_the_sequence",
                            "a_very_long_tool_name_number_two_for_the_sequence",
                            "a_very_long_tool_name_number_three_for_the_sequence",
                            "a_very_long_tool_name_number_four_for_the_sequence"),
                    3,
                    3,
                    0.0,
                    List.of());

    @Test
    void bodyAndDescriptionAreByteIdenticalToTheFormerCode() {
        for (ToolPattern p : List.of(WITH_GOALS, WITHOUT_GOALS, LONG_NAMES)) {
            assertThat(service.body(p, null)).isEqualTo(Former.body(p));
            assertThat(service.body(p, AGENT)).isEqualTo(Former.body(p));
            assertThat(service.description(p, null)).isEqualTo(Former.description(p));
        }
    }

    @Test
    void anAgentOverrideChangesOnlyThatAgentsDraft() {
        TenantContext.set(TenantContext.of("a", "owner", Set.of()));
        store.put(
                new PromptOverride(
                        PromptScope.AGENT,
                        AGENT.toString(),
                        SkillDistillationService.FOOTER_KEY,
                        "en",
                        "Always ask first.",
                        true,
                        1,
                        "owner",
                        Instant.now()));
        resolver.evictAll();

        assertThat(service.body(WITH_GOALS, AGENT)).endsWith("1. `search_flights`\n2. `book_flight`\nAlways ask first.");
        assertThat(service.body(WITH_GOALS, UUID.randomUUID())).isEqualTo(Former.body(WITH_GOALS));
    }

    /** The former {@code SkillDistillationService.description} and {@code body}, verbatim. */
    private static final class Former {
        static String description(SkillDistillationService.ToolPattern p) {
            String seq = SkillDistillationService.signature(p.tools());
            String d = "Distilled from " + p.runs() + " successful runs: " + seq + ".";
            return d.length() > 200 ? d.substring(0, 197) + "..." : d;
        }

        static String body(SkillDistillationService.ToolPattern p) {
            StringBuilder b = new StringBuilder();
            b.append("## Purpose\n\n");
            b.append("This procedure was distilled from ")
                    .append(p.runs())
                    .append(" successful runs of this agent (across ")
                    .append(p.distinctSessions())
                    .append(" separate sessions) that all followed the same tool sequence. ")
                    .append("It is a record of what demonstrably worked, not a guess.\n\n");
            if (!p.sampleGoals().isEmpty()) {
                b.append(
                        "## When to use\n\nWhen the task resembles the goals this pattern served, for example:\n\n");
                for (String goal : p.sampleGoals()) {
                    b.append("- ").append(goal).append('\n');
                }
                b.append('\n');
            }
            b.append("## Procedure\n\nFollow this tool sequence in order:\n\n");
            for (int i = 0; i < p.tools().size(); i++) {
                b.append(i + 1).append(". `").append(p.tools().get(i)).append("`\n");
            }
            b.append(
                    "\nIf a step errors, do not silently substitute a different tool - surface the failure. ");
            b.append(
                    "The sequence above is what was observed to succeed; departures from it are unverified.\n");
            return b.toString();
        }

    }
}
