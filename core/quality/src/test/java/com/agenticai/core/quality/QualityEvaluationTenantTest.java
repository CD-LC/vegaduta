package com.agenticai.core.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link EvalEngine#evaluateAsync} hops onto its own virtual-thread pool, and the judge evaluators
 * resolve the tenant's judge provider (BYOK key, per-tenant model) from {@code TenantContext}
 * through {@code JudgeChatClientFactory}, which a pool thread does not have. The evaluation must
 * run under the tenant of the caller that asked for it, and leave the caller's thread as it was.
 */
class QualityEvaluationTenantTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static String currentTenant() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse("NONE");
    }

    private static EvaluationContext context() {
        return new EvaluationContext(
                UUID.randomUUID(),
                "agent",
                "system",
                "question",
                "answer",
                List.of(),
                1,
                1,
                "model");
    }

    private static Evaluator recording(AtomicReference<String> seen) {
        return new Evaluator() {
            @Override
            public EvaluationType getType() {
                return EvaluationType.LENGTH;
            }

            @Override
            public EvaluationResult evaluate(EvaluationContext context) {
                seen.set(currentTenant());
                return EvaluationResult.of(EvaluationType.LENGTH, 0.8, "fine");
            }
        };
    }

    @Test
    void evaluateAsyncRunsTheEvaluatorsUnderTheCallersTenant() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>("unset");
        EvalEngine engine = new EvalEngine(List.of(recording(seen)));
        TenantContext.set(TenantContext.of("tenant-a", "alice", Set.of()));

        engine.evaluateAsync(context(), List.of(EvaluationType.LENGTH)).get(5, TimeUnit.SECONDS);

        assertEquals("tenant-a", seen.get());
        assertEquals("tenant-a", currentTenant(), "the caller's thread is left as it was");
    }

    @Test
    void evaluateAsyncWithNoTenantRunsWithNone() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>("unset");
        EvalEngine engine = new EvalEngine(List.of(recording(seen)));

        engine.evaluateAsync(context(), List.of(EvaluationType.LENGTH)).get(5, TimeUnit.SECONDS);

        assertEquals("NONE", seen.get());
    }
}
