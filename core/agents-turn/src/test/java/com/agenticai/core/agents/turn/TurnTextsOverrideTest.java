package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.AgentMemoryEntry;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.MemoryBlocks;
import com.agenticai.core.agents.memory.QueryEmbedding;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.memory.TenantMemoryEntry;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.agents.skills.SkillPromptBlock;
import com.agenticai.core.agents.skills.SkillService;
import com.agenticai.core.agents.skills.SkillSource;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.guardrails.moderation.ModerationDecision;
import com.agenticai.core.guardrails.moderation.ModerationOutcome;
import com.agenticai.core.guardrails.moderation.ModerationService;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptCatalog;
import com.agenticai.core.prompts.PromptContext;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * The texts the turn pipeline writes besides the standing directives (framing lines, refusals,
 * nudges, plan block, detector data) are registry keys: an override of the agent, the tenant or the
 * platform reaches every path that sends them. One test per path, each against the real class with
 * an in-memory override store.
 */
@SuppressWarnings("unchecked")
class TurnTextsOverrideTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 22);

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(
                    PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());
    private final PromptDirectiveSettings settings = PromptDirectiveSettings.forTest(resolver);

    @BeforeEach
    void tenant() {
        TenantContext.set(TenantContext.of("tenant-a", "owner", Set.of()));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void override(PromptScope scope, String key, String text) {
        store.put(
                new PromptOverride(
                        scope,
                        scope == PromptScope.AGENT ? AGENT.toString() : "",
                        key,
                        "en",
                        text,
                        true,
                        1,
                        "owner",
                        Instant.now()));
        resolver.evictAll();
    }

    private ResolvedDirectives pd() {
        return settings.forTurn(DAY, AGENT);
    }

    // ---- plan block ----

    @Test
    void thePlanBlockUsesTheOwnersWording() {
        override(PromptScope.TENANT, "planner.plan_block", "PLAN FOR YOU >> {plan}");

        List<Message> out = ChatPromptDirectives.prependPlanBlock("1. a", List.of(), pd());

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getText()).isEqualTo("PLAN FOR YOU >> 1. a");
    }

    // ---- memory recall framing ----

    @Test
    void theRecallFramingFollowsTheAgentThenTheTenant() {
        AgentService agentService = mock(AgentService.class);
        ObjectProvider<AgentMemoryService> agentProvider = mock(ObjectProvider.class);
        ObjectProvider<TenantMemoryService> tenantProvider = mock(ObjectProvider.class);
        AgentMemoryService agentMemory = mock(AgentMemoryService.class);
        TenantMemoryService tenant = mock(TenantMemoryService.class);
        when(agentProvider.getIfAvailable()).thenReturn(agentMemory);
        when(tenantProvider.getIfAvailable()).thenReturn(tenant);
        when(agentService.isShareMemoryWithTenant(AGENT)).thenReturn(true);
        when(agentMemory.recall(eq(AGENT), eq("alice"), eq("hi"), nullable(QueryEmbedding.class)))
                .thenReturn(
                        List.of(
                                new AgentMemoryEntry(
                                        UUID.randomUUID(),
                                        AGENT,
                                        "alice",
                                        "likes tea",
                                        0.0,
                                        Instant.now(),
                                        Instant.now(),
                                        0)));
        when(tenant.recall(
                        anyString(),
                        anyString(),
                        nullable(QueryEmbedding.class),
                        nullable(UUID.class)))
                .thenReturn(
                        List.of(
                                new TenantMemoryEntry(
                                        UUID.randomUUID(),
                                        "alice",
                                        "works nights",
                                        0.0,
                                        AGENT,
                                        Instant.now(),
                                        Instant.now(),
                                        0)));
        when(tenant.renderPromptBlocks("alice", "hi"))
                .thenReturn(MemoryBlocks.defaultNotesHeader() + "\n[persona]\nBe brief.");
        ChatMemoryRecall recall =
                new ChatMemoryRecall(agentProvider, tenantProvider, agentService, () -> false);
        recall.usePrompts(a -> settings.forTurn(DAY, a));
        override(PromptScope.TENANT, "memory.recall.private", "TENANT MEMORY:\n{facts}");
        override(PromptScope.AGENT, "memory.recall.private", "AGENT MEMORY:\n{facts}");
        override(PromptScope.TENANT, "memory.recall.shared", "TEAM MEMORY:\n{facts}");
        override(PromptScope.TENANT, "memory.notes", "Your own notes:");

        String out = recall.recallMemoryFactsBlock(AGENT, "alice", "hi");

        assertThat(out)
                .isEqualTo(
                        "Your own notes:\n[persona]\nBe brief.\n\nAGENT MEMORY:\n- likes tea\n\nTEAM MEMORY:\n- works nights");
        // another agent gets the tenant's wording, not this agent's
        ChatMemoryRecall other =
                new ChatMemoryRecall(agentProvider, tenantProvider, agentService, () -> false);
        other.usePrompts(a -> settings.forTurn(DAY, OTHER_AGENT));
        when(agentService.isShareMemoryWithTenant(OTHER_AGENT)).thenReturn(false);
        when(agentMemory.recall(
                        eq(OTHER_AGENT), eq("alice"), eq("hi"), nullable(QueryEmbedding.class)))
                .thenReturn(
                        List.of(
                                new AgentMemoryEntry(
                                        UUID.randomUUID(),
                                        OTHER_AGENT,
                                        "alice",
                                        "likes tea",
                                        0.0,
                                        Instant.now(),
                                        Instant.now(),
                                        0)));
        assertThat(other.recallMemoryFactsBlock(OTHER_AGENT, "alice", "hi"))
                .isEqualTo("TENANT MEMORY:\n- likes tea");
    }

    // ---- skills header, through the one place that composes the system text ----

    @Test
    void theSkillsHeaderIsTheOwnersInTheSystemTextAndTheDefaultElsewhere() {
        List<SkillService.SkillMetadata> skills =
                List.of(
                        new SkillService.SkillMetadata(
                                null, "xlsx", "Make sheets", SkillSource.BUILT_IN, true, 0));
        String shipped = SkillPromptBlock.render(skills);
        String shippedCompact = SkillPromptBlock.renderCompact(skills);
        override(
                PromptScope.TENANT,
                SkillPromptBlock.HEADER_KEY,
                "## Skills\nUse a skill when it fits.");
        override(PromptScope.AGENT, SkillPromptBlock.COMPACT_HEADER_KEY, "## Skills\nPick one:");

        ChatSystemPromptComposer.Composed full = compose(shipped, false);
        ChatSystemPromptComposer.Composed compact = compose(shippedCompact, true);

        assertThat(full.systemPrompt())
                .startsWith("BASE\n\n## Skills\nUse a skill when it fits.\n- xlsx: Make sheets");
        assertThat(full.systemPrompt()).doesNotContain("FIRST action");
        assertThat(compact.systemPrompt()).isEqualTo("BASE\n\n## Skills\nPick one: xlsx.\n");
    }

    @Test
    void aSkillsBlockWithTheShippedHeaderIsLeftExactlyAsItWas() {
        List<SkillService.SkillMetadata> skills =
                List.of(
                        new SkillService.SkillMetadata(
                                null, "xlsx", "Make sheets", SkillSource.BUILT_IN, true, 0));
        String shipped = SkillPromptBlock.render(skills);

        assertThat(compose(shipped, false).systemPrompt()).isEqualTo("BASE" + shipped);
        assertThat(SkillPromptBlock.withHeader(shipped, null, false)).isSameAs(shipped);
        assertThat(SkillPromptBlock.withHeader("something else", "## X", false))
                .isEqualTo("something else");
    }

    private ChatSystemPromptComposer.Composed compose(String skillsBlock, boolean smallModel) {
        return ChatSystemPromptComposer.compose(
                new ChatSystemPromptComposer.Input(
                        "BASE",
                        false,
                        "",
                        skillsBlock,
                        DAY,
                        pd(),
                        PromptDirectiveComposer.standard(() -> true, () -> true),
                        List.of(),
                        false,
                        false,
                        smallModel,
                        new ChatSystemPromptComposer.Switches(
                                false, false, false, false, false, false, false, ""),
                        AGENT,
                        null,
                        null,
                        null));
    }

    // ---- refusals ----

    @Test
    void theModerationRefusalsAndTheCheckNoticeUseTheOwnersWording() {
        ModerationService moderation = mock(ModerationService.class);
        ObjectProvider<ModerationService> moderationProvider = mock(ObjectProvider.class);
        when(moderationProvider.getIfAvailable()).thenReturn(moderation);
        ChatGuardrailGate gate =
                new ChatGuardrailGate(
                        moderationProvider,
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class),
                        null);
        gate.prompts = a -> settings.forTurn(DAY, a);
        override(PromptScope.TENANT, "refusal.moderation.blocked", "Not allowed here.");
        override(PromptScope.AGENT, "refusal.moderation.queued", "A person will look at it.");
        override(PromptScope.PLATFORM, "refusal.safety_check_unavailable", "Check offline.");

        when(moderation.moderate(anyString(), any()))
                .thenReturn(
                        new ModerationOutcome(ModerationDecision.BLOCK, null, null, 99, "x", null));
        assertThat(
                        gate.moderateInbound(AGENT, "s", "alice", "m", "tenant-a", null)
                                .collectList()
                                .block())
                .containsExactly("Not allowed here.");

        when(moderation.moderate(anyString(), any()))
                .thenReturn(
                        new ModerationOutcome(
                                ModerationDecision.QUEUE_FOR_REVIEW,
                                null,
                                null,
                                50,
                                "x",
                                UUID.randomUUID()));
        assertThat(
                        gate.moderateInbound(AGENT, "s", "alice", "m", "tenant-a", null)
                                .collectList()
                                .block())
                .containsExactly("A person will look at it.");
        // the other agent has no agent-scope override: the shipped queued text
        assertThat(
                        gate.moderateInbound(OTHER_AGENT, "s", "alice", "m", "tenant-a", null)
                                .collectList()
                                .block())
                .containsExactly(
                        PromptResolver.defaultsOnly()
                                .catalog()
                                .defaultText("refusal.moderation.queued"));

        when(moderation.moderate(anyString(), any())).thenThrow(new IllegalStateException("down"));
        assertThat(
                        gate.moderateInbound(AGENT, "s", "alice", "m", "tenant-a", null)
                                .collectList()
                                .block())
                .containsExactly("Check offline.");
    }

    @Test
    void theFabricatedLinkCorrectionAndTheGenericFailureAreStoredInTheOwnersWords() {
        ChatIssueRecorder issues =
                new ChatIssueRecorder(mock(AgentService.class), mock(ObjectProvider.class));
        issues.prompts = a -> settings.forTurn(DAY, a);
        override(
                PromptScope.TENANT, "message.fabricated_url.correction", "That link was invented.");
        override(PromptScope.TENANT, "message.generic_failure", "We hit a snag.");

        assertThat(
                        issues.recordAndCorrectFabricatedGenerationUrl(
                                AGENT, "s", "https://x.test/a.pdf"))
                .isEqualTo("That link was invented.");

        SessionMemoryService memory = mock(SessionMemoryService.class);
        ChatFailedReply failed = new ChatFailedReply(memory);
        failed.prompts = a -> settings.forTurn(DAY, a);
        // the error callback runs on a thread with no tenant: the sentence was taken on this one
        Flux<String> stream =
                failed.withFailedReplyPersistence(
                        Flux.error(new RuntimeException("boom")), AGENT, "s", () -> null);
        TenantContext.clear();
        stream.onErrorResume(e -> Flux.empty()).blockLast();
        ArgumentCaptor<ChatTurn> stored = ArgumentCaptor.forClass(ChatTurn.class);
        verify(memory).appendTurn(eq("s"), stored.capture());
        assertThat(stored.getValue().content())
                .isEqualTo(ChatTurn.FAILED_REPLY_PREFIX + "We hit a snag.");
    }

    // ---- nudges: one mechanism (the registry); an override wins ----

    @Test
    void aNudgeOverrideBeatsTheShippedTextForItsAgentOnly() {
        TurnContinuation policy = new TurnContinuation(new ContinuationProperties());
        String shipped =
                PromptResolver.defaultsOnly().catalog().defaultText(NudgeTexts.CONTINUE_UNFINISHED);
        override(PromptScope.AGENT, "nudge.continue_unfinished", "Go on and use the tools.");
        override(PromptScope.TENANT, "nudge.reply_cut_off", "Keep writing.");

        assertThat(policy.nudge("en", pd())).isEqualTo("Go on and use the tools.");
        assertThat(policy.nudge("en", settings.forTurn(DAY, OTHER_AGENT))).isEqualTo(shipped);
        assertThat(pd().text(NudgeTexts.REPLY_CUT_OFF)).isEqualTo("Keep writing.");
        assertThat(pd().text(NudgeTexts.RETRY_TOOL_CALL))
                .isEqualTo(
                        PromptResolver.defaultsOnly()
                                .catalog()
                                .defaultText(NudgeTexts.RETRY_TOOL_CALL));
        assertThat(ResolvedDirectives.DEFAULTS.text(NudgeTexts.REPLY_CUT_OFF))
                .isEqualTo(
                        PromptResolver.defaultsOnly()
                                .catalog()
                                .defaultText(NudgeTexts.REPLY_CUT_OFF));
    }

    // ---- degraded step event ----

    @Test
    void theDegradedStepEventUsesTheOwnersWording() {
        ChatTurnExecutor executor =
                new ChatTurnExecutor(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        java.time.Duration.ofSeconds(30));
        executor.setMeterRegistry(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        executor.prompts = a -> settings.forTurn(DAY, a);
        override(
                PromptScope.TENANT,
                "message.degraded.tools_disabled",
                "Tools were off for this answer.");
        AgentStepEventEmitter emitter = mock(AgentStepEventEmitter.class);
        when(emitter.isEnabled()).thenReturn(true);
        Agent agent = mock(Agent.class);
        TurnContext context = mock(TurnContext.class);
        when(context.scope()).thenReturn(com.agenticai.core.security.TenantScope.capture());

        executor.flagToolsDisabledRetry(agent, emitter, context, AGENT);

        verify(emitter).guardrail("tools", "Tools were off for this answer.");
    }

    // ---- fabricated-link detector data ----

    @Test
    void theDetectorListsFollowTheOwnersOverrides() {
        String link = "https://files.example.test/q3.pdf";
        assertThat(ChatPromptDirectives.containsFabricatedGenerationUrl("Done: " + link, pd()))
                .isFalse();
        assertThat(
                        ChatPromptDirectives.containsFabricatedGenerationUrl(
                                "https://evil.example.test/x", pd()))
                .isFalse();

        override(
                PromptScope.TENANT,
                ChatPromptDirectives.FABRICATED_URL_CLAIM_PHRASES_KEY,
                "done:\nis ready");
        override(
                PromptScope.TENANT,
                ChatPromptDirectives.FABRICATED_URL_MARKERS_KEY,
                "evil.example.test");

        assertThat(ChatPromptDirectives.containsFabricatedGenerationUrl("Done: " + link, pd()))
                .isTrue();
        assertThat(
                        ChatPromptDirectives.containsFabricatedGenerationUrl(
                                "https://evil.example.test/x", pd()))
                .isTrue();
        // the shipped list is the fallback for any other tenant or call without settings
        assertThat(ChatPromptDirectives.containsFabricatedGenerationUrl("Done: " + link)).isFalse();
    }

    @Test
    void aBrokenFileLinkExpressionNeverLeavesTheGuardBlind() {
        override(
                PromptScope.TENANT,
                ChatPromptDirectives.FABRICATED_URL_FILE_PATTERN_KEY,
                "([unclosed");

        assertThat(
                        ChatPromptDirectives.containsFabricatedGenerationUrl(
                                "Your report is ready: https://files.example.test/q3.pdf", pd()))
                .isTrue();
    }

    @Test
    void everyLocaleTheCatalogShipsIsChecked() throws IOException {
        Map<String, String> sources = new TreeMap<>();
        for (String key :
                List.of(
                        "guard.fabricated_url.markers",
                        "guard.fabricated_url.file_url_pattern",
                        "guard.fabricated_url.claim_phrases")) {
            sources.put("prompts/en/" + key + ".md", classpath("/prompts/en/" + key + ".md"));
        }
        sources.put("prompts/es/guard.fabricated_url.claim_phrases.md", "descarga\nya esta listo");
        PromptCatalog catalog = PromptCatalog.fromSources(sources, "en", 4000);
        assertThat(catalog.problems()).isEmpty();
        ResolvedDirectives multi =
                new ResolvedDirectives(
                        new DefaultPromptResolver(
                                catalog, new InMemoryPromptOverrideStore(), new PromptProperties()),
                        PromptContext.none());

        assertThat(
                        ChatPromptDirectives.containsFabricatedGenerationUrl(
                                "Aqui: descarga https://x.test/informe.pdf", multi))
                .isTrue();
        assertThat(
                        ChatPromptDirectives.containsFabricatedGenerationUrl(
                                "The download is at https://x.test/a.pdf", multi))
                .isTrue();
        assertThat(
                        ChatPromptDirectives.containsFabricatedGenerationUrl(
                                "Aqui: https://x.test/informe.pdf", multi))
                .isFalse();
    }

    private static String classpath(String path) throws IOException {
        try (InputStream in = TurnTextsOverrideTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---- the text lookups of ResolvedDirectives ----

    @Test
    void aSwitchedOffOrUnknownTextStillGivesThePersonSomethingToRead() {
        assertThat(pd().textOrShipped("refusal.moderation.blocked"))
                .isEqualTo(
                        PromptResolver.defaultsOnly()
                                .catalog()
                                .defaultText("refusal.moderation.blocked"));
        assertThat(pd().text("no.such_key")).isNull();
        List<ResolvedDirectives> seen = new ArrayList<>();
        seen.add(pd().recording());
        seen.get(0).textOrShipped("message.generic_failure");
        assertThat(seen.get(0).trace())
                .extracting(r -> r.key())
                .containsExactly("message.generic_failure");
    }
}
