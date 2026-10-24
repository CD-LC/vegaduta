package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.turn.TurnReplyOutcome.Kind;
import com.agenticai.core.agents.turn.TurnReplyOutcome.State;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptContext;
import com.agenticai.core.prompts.PromptDefaultAlias;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The notices the person reads when a reply is not an answer (cut off, stopped by the provider's safety system,
 * unusable tool call, empty) are prompt-registry keys: byte-identical to the property literals they replaced, an
 * override of the tenant or the agent applies on the real {@link ReplyFinish#notice} path, and the deprecated
 * {@code app.chat.finish.*} properties still seed the platform default.
 */
class FinishNoticePromptTest {

    private static final String TRUNCATED =
            "[This reply was cut off because the model reached its output length limit. Ask for the rest, or for a shorter answer.]";
    private static final String REFUSAL =
            "The model provider's safety system stopped this reply ({reason}), so there is no answer to show for this request. If you think it was stopped by mistake, change the request or tell your administrator.";
    private static final String MALFORMED =
            "The model tried to use a tool but its tool call was invalid, so nothing was run and there is no answer to show. Please try again.";
    private static final String EMPTY =
            "The model returned an empty reply, so there is nothing to show. Please try again.";

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private DefaultPromptResolver resolver(List<PromptDefaultAlias> aliases) {
        return new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties(), aliases);
    }

    private ResolvedDirectives directives(PromptResolver resolver, UUID agent) {
        return new ResolvedDirectives(resolver, PromptContext.forAgent(agent));
    }

    @Test
    void theShippedKeysAreByteIdenticalToThePropertyLiteralsTheyReplaced() {
        PromptResolver shipped = PromptResolver.defaultsOnly();
        assertThat(shipped.text(ReplyFinish.TRUNCATED_KEY)).isEqualTo(TRUNCATED);
        assertThat(shipped.text(ReplyFinish.REFUSAL_KEY)).isEqualTo(REFUSAL);
        assertThat(shipped.text(ReplyFinish.MALFORMED_TOOL_CALL_KEY)).isEqualTo(MALFORMED);
        assertThat(shipped.text(ReplyFinish.EMPTY_REPLY_KEY)).isEqualTo(EMPTY);
        assertThat(shipped.catalog().problems()).isEmpty();
        // the deprecated properties default to the same text
        ChatFinishProperties p = new ChatFinishProperties();
        assertThat(p.getTruncatedNotice()).isEqualTo(TRUNCATED);
        assertThat(p.getRefusalNotice()).isEqualTo(REFUSAL);
        assertThat(p.getMalformedToolCallNotice()).isEqualTo(MALFORMED);
        assertThat(p.getEmptyReplyMessage()).isEqualTo(EMPTY);
    }

    @Test
    void noticeResolvesFromTheRegistryAndFillsTheProviderReason() {
        ChatFinishProperties cfg = new ChatFinishProperties();
        ResolvedDirectives defaults = ResolvedDirectives.DEFAULTS;
        assertThat(ReplyFinish.notice(cfg, new State(Kind.TRUNCATED, null), defaults)).isEqualTo(TRUNCATED);
        assertThat(ReplyFinish.notice(cfg, new State(Kind.MALFORMED_TOOL_CALL, null), defaults)).isEqualTo(MALFORMED);
        assertThat(ReplyFinish.notice(cfg, new State(Kind.EMPTY, null), defaults)).isEqualTo(EMPTY);
        assertThat(ReplyFinish.notice(cfg, new State(Kind.REFUSED, " content_filter "), defaults))
                .isEqualTo(REFUSAL.replace("{reason}", "content_filter"))
                .isEqualTo(cfg.renderRefusalNotice("content_filter"));
        assertThat(ReplyFinish.notice(cfg, new State(Kind.COMPLETE, null), defaults)).isNull();
        assertThat(ReplyFinish.notice(cfg, new State(Kind.INTERRUPTED, null), defaults)).isNull();
    }

    @Test
    void aTenantOverrideAndAnAgentOverrideApplyAndTheAgentWins() {
        UUID agent = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        DefaultPromptResolver resolver = resolver(List.of());
        TenantContext.set(TenantContext.of("t1", "owner", Set.of()));
        store.put(new PromptOverride(PromptScope.TENANT, "", ReplyFinish.EMPTY_REPLY_KEY, "en", "Tenant: nothing came back.", true, 1, "owner", Instant.now()));
        store.put(new PromptOverride(PromptScope.AGENT, agent.toString(), ReplyFinish.EMPTY_REPLY_KEY, "en", "Agent: nothing came back.", true, 1, "owner", Instant.now()));
        store.put(new PromptOverride(PromptScope.TENANT, "", ReplyFinish.REFUSAL_KEY, "en", "Blocked ({reason}).", true, 1, "owner", Instant.now()));
        resolver.evictAll();
        ChatFinishProperties cfg = new ChatFinishProperties();
        State empty = new State(Kind.EMPTY, null);

        assertThat(ReplyFinish.notice(cfg, empty, directives(resolver, agent))).isEqualTo("Agent: nothing came back.");
        assertThat(ReplyFinish.notice(cfg, empty, directives(resolver, other))).isEqualTo("Tenant: nothing came back.");
        assertThat(ReplyFinish.notice(cfg, new State(Kind.REFUSED, "SAFETY"), directives(resolver, other)))
                .isEqualTo("Blocked (SAFETY).");
    }

    @Test
    void aChangedDeprecatedPropertySeedsThePlatformDefaultButAnOverrideStillWins() {
        ChatFinishProperties changed = new ChatFinishProperties();
        changed.setTruncatedNotice("[Cut off - ask me to go on.]");
        changed.setEmptyReplyMessage(EMPTY); // unchanged: no alias effect
        TenantContext.set(TenantContext.of("t1", "owner", Set.of()));
        DefaultPromptResolver resolver =
                resolver(
                        List.of(
                                PromptPropertyAliases.truncatedNotice(changed),
                                PromptPropertyAliases.emptyReplyMessage(changed)));
        ChatFinishProperties cfg = new ChatFinishProperties();
        assertThat(ReplyFinish.notice(cfg, new State(Kind.TRUNCATED, null), directives(resolver, null)))
                .isEqualTo("[Cut off - ask me to go on.]");
        assertThat(ReplyFinish.notice(cfg, new State(Kind.EMPTY, null), directives(resolver, null))).isEqualTo(EMPTY);

        store.put(new PromptOverride(PromptScope.TENANT, "", ReplyFinish.TRUNCATED_KEY, "en", "[Tenant cut-off text.]", true, 1, "owner", Instant.now()));
        resolver.evictAll();
        assertThat(ReplyFinish.notice(cfg, new State(Kind.TRUNCATED, null), directives(resolver, null)))
                .isEqualTo("[Tenant cut-off text.]");
    }
}
