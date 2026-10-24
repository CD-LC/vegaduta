package com.agenticai.core.agents.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.context.ContextCompactionEngine.Params;
import com.agenticai.core.agents.context.ContextCompactionEngine.Texts;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptContext;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * The summary that replaces old turns and the note that replaces a cleared tool result are worded by
 * the prompt registry ({@code compaction.*}); a run takes its wording once, so the engine recognises
 * a summary by the same text that wrote it.
 */
class CompactionTextsOverrideTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void override(PromptScope scope, String key, String text) {
        store.put(
                new PromptOverride(
                        scope, scope == PromptScope.AGENT ? AGENT.toString() : "", key, "en", text, true, 1, "owner", Instant.now()));
        resolver.evictAll();
    }

    @Test
    void theShippedNoteIsTheOldText() {
        var response = new ToolResponseMessage.ToolResponse("1", "search", "ok");

        String withRestore = ContextCompactionEngine.placeholder(response, "{\"q\":1}", true);
        String without = ContextCompactionEngine.placeholder(response, "{\"q\":1}", false);

        assertThat(withRestore)
                .contains("\"restore\":\"call restore_tool_result with stepId \\\"")
                .contains("\\\", or run the tool again\"");
        assertThat(without).contains("\"restore\":\"run the tool again if you need it\"");
    }

    @Test
    void theWordingIsTheShippedOneUnlessTheOwnerStoredAnother() {
        TenantContext.set(TenantContext.of("a", "owner", Set.of()));
        assertThat(Texts.build(resolver, PromptContext.forAgent(AGENT))).isEqualTo(Texts.shipped());

        override(PromptScope.TENANT, "compaction.summary_prefix", "[Recap:]");
        override(PromptScope.AGENT, "compaction.restore_hint", "restore step {stepId} or rerun");
        override(PromptScope.TENANT, "compaction.restore_hint.no_restore", "rerun it");
        Texts texts = Texts.build(resolver, PromptContext.forAgent(AGENT));

        assertThat(texts.summaryPrefix()).isEqualTo("[Recap:]\n");
        assertThat(texts.restoreHint()).isEqualTo("restore step {stepId} or rerun");
        assertThat(texts.noRestoreHint()).isEqualTo("rerun it");
    }

    @Test
    void theClearedResultNoteCarriesTheOwnersHint() {
        TenantContext.set(TenantContext.of("a", "owner", Set.of()));
        override(PromptScope.TENANT, "compaction.restore_hint", "restore step {stepId} or rerun");
        override(PromptScope.TENANT, "compaction.restore_hint.no_restore", "rerun it");
        Texts texts = Texts.build(resolver, PromptContext.forAgent(AGENT));
        var response = new ToolResponseMessage.ToolResponse("1", "search", "ok");

        String withRestore = ContextCompactionEngine.placeholder(response, "{\"q\":1}", true, texts);
        String without = ContextCompactionEngine.placeholder(response, "{\"q\":1}", false, texts);

        assertThat(withRestore).contains("\"restore\":\"restore step ").contains(" or rerun\"").doesNotContain("{stepId}");
        assertThat(without).contains("\"restore\":\"rerun it\"");
    }

    @Test
    void aSummaryWrittenWithTheOwnersPrefixIsRecognisedAndRolledOnTheNextPass() {
        TenantContext.set(TenantContext.of("a", "owner", Set.of()));
        override(PromptScope.TENANT, "compaction.summary_prefix", "[Recap:]");
        Texts texts = Texts.build(resolver, PromptContext.forAgent(AGENT));
        Params params = new Params(10_000, 0.6, 0.85, 2, 2, 100, true, null, texts);
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("system"));
        for (int i = 0; i < 12; i++) {
            messages.add(new UserMessage("question " + i + " " + "x".repeat(2_000)));
            messages.add(new AssistantMessage("answer " + i + " " + "y".repeat(2_000)));
        }
        messages.add(new UserMessage("latest"));
        List<String> seenExisting = new ArrayList<>();
        ContextCompactionEngine engine = new ContextCompactionEngine();

        ContextCompactionEngine.Result first = engine.apply(messages, 0, params, (existing, turns) -> {
            seenExisting.add(existing);
            return Optional.of("older turns in brief");
        });

        assertThat(first.changed()).isTrue();
        assertThat(first.messages()).anyMatch(m -> m instanceof SystemMessage && m.getText().startsWith("[Recap:]\n"));
        assertThat(first.messages()).noneMatch(m -> m.getText().startsWith(ContextCompactionEngine.SUMMARY_PREFIX));
        assertThat(seenExisting).containsExactly((String) null);
    }
}
