package com.agenticai.core.agents.learning.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.learning.LearningProposalService;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** The review exists only when both it and the proposal store are on; the applier needs only the store. */
class MemoryReviewWiringTest {

    @Configuration
    @Import({MemoryReviewService.class, MemoryReviewSweep.class, LearningReviewController.class, MemoryConsolidationApplier.class})
    static class Wiring {
        @Bean
        LearningProposalService proposals() {
            return mock(LearningProposalService.class);
        }

        @Bean
        LearningReviewRunRepository runs() {
            return mock(LearningReviewRunRepository.class);
        }

        @Bean
        TenantSchemaIterator tenants() {
            return mock(TenantSchemaIterator.class);
        }

        @Bean
        PgAdvisoryJobLock lock() {
            return mock(PgAdvisoryJobLock.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Wiring.class);

    private static int count(org.springframework.context.ApplicationContext ctx) {
        return ctx.getBeanNamesForType(MemoryReviewService.class).length
                + ctx.getBeanNamesForType(MemoryReviewSweep.class).length
                + ctx.getBeanNamesForType(LearningReviewController.class).length;
    }

    @Test
    void offByDefault() {
        runner.run(ctx -> {
            assertEquals(0, count(ctx));
            assertEquals(0, ctx.getBeanNamesForType(MemoryConsolidationApplier.class).length);
        });
    }

    @Test
    void reviewFlagWithoutTheStoreCreatesNothing() {
        runner.withPropertyValues("app.learning.review.enabled=true").run(ctx -> assertEquals(0, count(ctx)));
    }

    @Test
    void storeOnlyRegistersTheApplierButNotTheReview() {
        runner.withPropertyValues("app.learning.proposals.enabled=true").run(ctx -> {
            assertEquals(0, count(ctx));
            assertEquals(1, ctx.getBeanNamesForType(MemoryConsolidationApplier.class).length);
        });
    }

    @Test
    void bothFlagsCreateServiceSweepAndController() {
        runner.withPropertyValues("app.learning.proposals.enabled=true", "app.learning.review.enabled=true")
                .run(ctx -> assertEquals(3, count(ctx)));
    }
}
