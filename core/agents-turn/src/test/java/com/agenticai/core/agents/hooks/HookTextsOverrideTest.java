package com.agenticai.core.agents.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.guardrails.ToolResultMarker;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The words the hook engine writes ({@code message.hook.*}) follow the agent's, tenant's and
 * platform's overrides, a hook author's own reason or message always wins over the default
 * wording, and a refusal stays recognisable as "the tool did not run" whatever its wording.
 */
class HookTextsOverrideTest extends HookEngineTestBase {

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());

    @org.junit.jupiter.api.BeforeEach
    void tenant() {
        TenantContext.set(TenantContext.of("tenant-a", "owner", Set.of()));
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private HookCtx tenantCtx() {
        return new HookCtx(
                AGENT,
                "sess-1",
                "tenant-a",
                emitter,
                TenantScope.of(TenantContext.of("tenant-a", "owner", Set.of())),
                List.of());
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

    private HookEngine wiredEngine() {
        HookEngine engine = engine();
        engine.usePrompts(resolver);
        return engine;
    }

    private static final EffectiveHook HIDE_RESULT =
            hook("hide", HookPoint.AFTER_TOOL, HookActionType.BLOCK, Map.of());

    @Test
    void withNoOverrideTheWordingIsTheShippedOne() {
        String out = wiredEngine().afterTool(tenantCtx(), List.of(HIDE_RESULT), "crm", "x");

        assertEquals(HookEngine.withheldResult("crm", "this is blocked by a workspace policy"), out);
        assertTrue(out.startsWith("Tool output from 'crm' was withheld"), out);
    }

    @Test
    void aTenantRewordingOfTheFramingAndTheDefaultReasonApplies() {
        override(PromptScope.TENANT, "message.hook.tool_output_withheld", "Hidden {tool}: {reason}.");
        override(PromptScope.TENANT, "message.hook.default_block_reason", "company rule 7");

        String out = wiredEngine().afterTool(tenantCtx(), List.of(HIDE_RESULT), "crm", "x");

        assertEquals("Hidden crm: company rule 7.", out);
        // still the platform's answer, whatever the words: the journals must not record an executed effect
        assertEquals(ToolResultMarker.Kind.WITHHELD, ToolResultMarker.kindOf(out).orElseThrow());
    }

    @Test
    void theHookAuthorsOwnReasonBeatsTheDefaultWording() {
        override(PromptScope.TENANT, "message.hook.default_block_reason", "company rule 7");
        EffectiveHook withReason =
                hook("hide", HookPoint.AFTER_TOOL, HookActionType.BLOCK, Map.of("reason", "payroll data"));

        String out = wiredEngine().afterTool(tenantCtx(), List.of(withReason), "hr", "x");

        assertTrue(out.contains("payroll data"), out);
        assertTrue(!out.contains("company rule 7"), out);
    }

    @Test
    void theRefusalAPersonReadsFollowsTheAgentOverrideAndTheHookMessageStillWins() {
        override(PromptScope.AGENT, "message.hook.request_blocked", "Our policy stops that.");
        EffectiveHook block = hook("closed", HookPoint.BEFORE_MODEL, HookActionType.BLOCK, Map.of());
        EffectiveHook authored =
                hook("closed", HookPoint.BEFORE_MODEL, HookActionType.BLOCK, Map.of("reason", "Ask HR."));

        assertEquals("Our policy stops that.", wiredEngine().beforeModel(tenantCtx(), List.of(block), "hi").refusal());
        assertEquals("Ask HR.", wiredEngine().beforeModel(tenantCtx(), List.of(authored), "hi").refusal());
    }

    @Test
    void theContextNoteFramingIsTheOwnersWording() {
        override(PromptScope.TENANT, "message.hook.context_note", "FYI: {note}");
        EffectiveHook note =
                hook("n", HookPoint.BEFORE_MODEL, HookActionType.APPEND_CONTEXT, Map.of("note", "be kind"));

        assertEquals("FYI: be kind", wiredEngine().beforeModel(tenantCtx(), List.of(note), "hi").note());
        assertEquals("Workspace note: be kind", engine().beforeModel(ctx, List.of(note), "hi").note());
    }
}
