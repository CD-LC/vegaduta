package com.agenticai.core.guardrails.impl;

import com.agenticai.core.guardrails.ContentGuardrail;
import com.agenticai.core.guardrails.ContentGuardrailEngine;
import com.agenticai.core.guardrails.ContentGuardrailResult;
import com.agenticai.core.guardrails.ContentGuardrailType;
import com.agenticai.core.guardrails.GuardrailRuleAction;
import com.agenticai.core.guardrails.TenantGuardrailRuleEvaluator;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * The implementation behind {@link ContentGuardrailType#CUSTOM} - the one
 * guardrail type the API has always offered ("Custom Guardrail", UNLIMITED
 * tier) with nothing wired behind it. Before this class, enabling it on an
 * agent stored the string {@code "custom"} in {@code agent_enabled_guardrail}
 * and changed nothing: {@code ContentGuardrailEngine} had no bean for the type,
 * and {@code AgentChatService} excluded it from both path filters precisely
 * because there was no bean. It now runs the tenant's own
 * {@code guardrail_rule} rows.
 *
 * <p><b>Two different switches, deliberately.</b> A tenant rule also applies to
 * every TOOL result via {@code GuardedToolCallback}, with no per-agent toggle -
 * tool output is untrusted text arriving from outside the platform, and that is
 * the case the rules were seeded for. This guardrail is the per-agent opt-in
 * for applying those same rules to the CONVERSATION: what the user typed, and
 * what the agent said back.
 *
 * <p><b>Direction matters.</b> On the OUTPUT path a {@link
 * GuardrailRuleAction#REDACT} rule rewrites the reply in place. On the INPUT
 * path there is nowhere to put a rewrite - that gate returns a refusal or lets
 * the original message through untouched, deliberately, because it runs before
 * the response cache and the model client exist and the message text is the
 * cache key. The same applies to {@code OUTPUT_ALREADY_DELIVERED}, the FAST
 * depth's post-stream audit pass, where the reader has already seen every
 * token. So redaction is not even attempted on those paths rather than being
 * computed and silently dropped; BLOCK rules work on all of them, and are
 * audited on the streamed path even though they cannot withhold anything.
 */
@Component
public class CustomRuleContentGuardrail implements ContentGuardrail {

    private final TenantGuardrailRuleEvaluator evaluator;

    public CustomRuleContentGuardrail(TenantGuardrailRuleEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    @Override
    public ContentGuardrailType getType() {
        return ContentGuardrailType.CUSTOM;
    }

    @Override
    public ContentGuardrailResult evaluate(String content) {
        // No path supplied - assume the conservative one and never offer a
        // redaction the caller might not be able to use.
        return evaluate(content, null, null);
    }

    @Override
    public ContentGuardrailResult evaluate(
        String content, ChatClient chatClient, ContentGuardrailEngine.GuardrailEvaluationPath path
    ) {
        boolean allowRedaction = path == ContentGuardrailEngine.GuardrailEvaluationPath.OUTPUT;
        TenantGuardrailRuleEvaluator.Outcome outcome = evaluator.apply(content, allowRedaction);
        if (outcome.blocked()) {
            return ContentGuardrailResult.fail(
                ContentGuardrailType.CUSTOM,
                // The rule NAME is deliberately in the user-facing reason: this
                // is the tenant's own rule, and "which of my rules stopped
                // this" is the first question they will ask. Rule names are
                // tenant-authored, so nothing here leaks across tenants.
                "This content matched one of your workspace's guardrail rules (\"" + outcome.blockedByRuleName()
                    + "\") and was not allowed through.",
                // A tenant-authored literal pattern match is an exact,
                // deterministic hit, not a heuristic guess - unlike the
                // semantic guardrails, whose confidence is a model's opinion.
                100
            );
        }
        if (outcome.redacted()) {
            return ContentGuardrailResult.redact(ContentGuardrailType.CUSTOM, outcome.text());
        }
        return ContentGuardrailResult.pass(ContentGuardrailType.CUSTOM);
    }
}
