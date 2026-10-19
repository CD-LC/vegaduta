package com.agenticai.core.guardrails.impl;

import com.agenticai.core.guardrails.ContentGuardrail;
import com.agenticai.core.guardrails.ContentGuardrailCheckResult;
import com.agenticai.core.guardrails.ContentGuardrailEngine;
import com.agenticai.core.guardrails.ContentGuardrailResult;
import com.agenticai.core.guardrails.ContentGuardrailType;
import com.agenticai.core.guardrails.TenantGuardrailRuleEvaluator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CustomRuleContentGuardrail} - the implementation behind
 * {@link ContentGuardrailType#CUSTOM}, which the API offered for months with no
 * bean behind it, plus the redaction channel it needed
 * {@link ContentGuardrailEngine} to grow.
 */
class CustomRuleContentGuardrailTest {

    private static final UUID AGENT_ID = UUID.randomUUID();

    /**
     * Same deterministic provider ContentGuardrailEngineTest uses (and for the
     * same reason - a Mockito stub of {@code getIfAvailable(Supplier)} can
     * never match the method reference the engine passes).
     */
    private static ContentGuardrailEngine engineWith(ContentGuardrail... guardrails) {
        List<ContentGuardrail> list = List.of(guardrails);
        return new ContentGuardrailEngine(new ObjectProvider<>() {
            @Override
            public List<ContentGuardrail> getIfAvailable() {
                return list;
            }
        });
    }

    @Test
    @DisplayName("a blocking tenant rule fails the check and names the rule in the user-facing reason")
    void blockingRuleFails() {
        TenantGuardrailRuleEvaluator evaluator = mock(TenantGuardrailRuleEvaluator.class);
        when(evaluator.apply(anyString(), anyBoolean()))
            .thenReturn(new TenantGuardrailRuleEvaluator.Outcome("secret", false, "No codenames"));

        ContentGuardrailResult result = new CustomRuleContentGuardrail(evaluator).evaluate("secret");

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getReason()).contains("No codenames");
        assertThat(result.getGuardrailType()).isEqualTo(ContentGuardrailType.CUSTOM);
    }

    @Test
    @DisplayName("redaction is offered on the OUTPUT path and never attempted on the INPUT path")
    void redactionOnlyOnOutputPath() {
        TenantGuardrailRuleEvaluator evaluator = mock(TenantGuardrailRuleEvaluator.class);
        when(evaluator.apply(anyString(), anyBoolean()))
            .thenReturn(new TenantGuardrailRuleEvaluator.Outcome("clean", false, null));
        CustomRuleContentGuardrail guardrail = new CustomRuleContentGuardrail(evaluator);

        guardrail.evaluate("text", null, ContentGuardrailEngine.GuardrailEvaluationPath.OUTPUT);
        verify(evaluator).apply("text", true);

        guardrail.evaluate("text", null, ContentGuardrailEngine.GuardrailEvaluationPath.INPUT);
        verify(evaluator).apply("text", false);
    }

    @Test
    @DisplayName("the FAST depth's post-stream audit pass does not attempt a redaction either")
    void noRedactionOnceTheAnswerHasBeenDelivered() {
        // AgentChatService#auditStreamedOutboundViolation evaluates an answer
        // whose tokens are already on the reader's screen. A rewrite there
        // cannot change anything, so it must not be computed - otherwise every
        // streamed turn pays for a regex pass whose result is discarded, and
        // logs that it redacted something that was never redacted.
        TenantGuardrailRuleEvaluator evaluator = mock(TenantGuardrailRuleEvaluator.class);
        when(evaluator.apply(anyString(), anyBoolean()))
            .thenReturn(new TenantGuardrailRuleEvaluator.Outcome("streamed", false, null));

        new CustomRuleContentGuardrail(evaluator).evaluate(
            "streamed", null, ContentGuardrailEngine.GuardrailEvaluationPath.OUTPUT_ALREADY_DELIVERED);

        verify(evaluator).apply("streamed", false);
    }

    @Test
    @DisplayName("a BLOCK rule is still detected after delivery, so the audit row is still written")
    void blockingStillDetectedAfterDelivery() {
        TenantGuardrailRuleEvaluator evaluator = mock(TenantGuardrailRuleEvaluator.class);
        when(evaluator.apply(anyString(), anyBoolean()))
            .thenReturn(new TenantGuardrailRuleEvaluator.Outcome("leaked", false, "No codenames"));

        ContentGuardrailResult result = new CustomRuleContentGuardrail(evaluator).evaluate(
            "leaked", null, ContentGuardrailEngine.GuardrailEvaluationPath.OUTPUT_ALREADY_DELIVERED);

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getReason()).contains("No codenames");
    }

    @Test
    @DisplayName("the engine hands back the redacted text so the caller can use it instead of its own copy")
    void engineReturnsRedactedContent() {
        TenantGuardrailRuleEvaluator evaluator = mock(TenantGuardrailRuleEvaluator.class);
        when(evaluator.apply(eq("card 4111111111111"), anyBoolean()))
            .thenReturn(new TenantGuardrailRuleEvaluator.Outcome("card [REDACTED]", true, null));
        ContentGuardrailEngine engine = engineWith(new CustomRuleContentGuardrail(evaluator));

        ContentGuardrailCheckResult result = engine.evaluate(
            "card 4111111111111",
            Set.of(ContentGuardrailType.CUSTOM),
            ContentGuardrailEngine.GuardrailEvaluationContext.output(AGENT_ID, "session-1", null),
            null);

        assertThat(result.isPassed()).isTrue();
        assertThat(result.getSanitizedContent()).isEqualTo("card [REDACTED]");
    }

    @Test
    @DisplayName("a later guardrail sees the redacted text, not the raw text")
    void redactionIsVisibleToLaterGuardrails() {
        TenantGuardrailRuleEvaluator evaluator = mock(TenantGuardrailRuleEvaluator.class);
        when(evaluator.apply(eq("leak sk-abc"), anyBoolean()))
            .thenReturn(new TenantGuardrailRuleEvaluator.Outcome("leak [REDACTED]", true, null));
        RecordingGuardrail downstream = new RecordingGuardrail();
        ContentGuardrailEngine engine = engineWith(new CustomRuleContentGuardrail(evaluator), downstream);

        engine.evaluate(
            "leak sk-abc",
            // LinkedHashSet, not Set.of: the engine evaluates in the caller's
            // iteration order, and this test is specifically about what the
            // SECOND guardrail sees. (AgentChatService passes an EnumSet, where
            // CUSTOM sorts last by ordinal - so in production a tenant
            // redaction is the final word rather than an input to the others.)
            new LinkedHashSet<>(List.of(ContentGuardrailType.CUSTOM, ContentGuardrailType.PROFANITY)),
            ContentGuardrailEngine.GuardrailEvaluationContext.output(AGENT_ID, "session-1", null),
            null);

        assertThat(downstream.seen).isEqualTo("leak [REDACTED]");
    }

    @Test
    @DisplayName("a passing check still reports the content, so callers can read it unconditionally")
    void passingCheckCarriesContent() {
        TenantGuardrailRuleEvaluator evaluator = mock(TenantGuardrailRuleEvaluator.class);
        when(evaluator.apply(anyString(), anyBoolean()))
            .thenReturn(new TenantGuardrailRuleEvaluator.Outcome("all fine", false, null));
        ContentGuardrailEngine engine = engineWith(new CustomRuleContentGuardrail(evaluator));

        ContentGuardrailCheckResult result = engine.evaluate(
            "all fine",
            Set.of(ContentGuardrailType.CUSTOM),
            ContentGuardrailEngine.GuardrailEvaluationContext.output(AGENT_ID, "session-1", null),
            null);

        assertThat(result.isPassed()).isTrue();
        assertThat(result.getSanitizedContent()).isEqualTo("all fine");
    }

    /** Records whatever text the engine actually handed it. */
    private static final class RecordingGuardrail implements ContentGuardrail {
        private String seen;

        @Override
        public ContentGuardrailType getType() {
            return ContentGuardrailType.PROFANITY;
        }

        @Override
        public ContentGuardrailResult evaluate(String content) {
            this.seen = content;
            return ContentGuardrailResult.pass(ContentGuardrailType.PROFANITY);
        }
    }
}
