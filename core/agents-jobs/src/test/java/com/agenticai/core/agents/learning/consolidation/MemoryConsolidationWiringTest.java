package com.agenticai.core.agents.learning.consolidation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** The consolidation sweep exists only behind its flag, off by default. */
class MemoryConsolidationWiringTest {

    @Configuration
    @Import({MemoryConsolidationService.class, MemoryConsolidationSweep.class})
    static class Wiring {
        @Bean
        ConsolidationFactSource facts() {
            return mock(ConsolidationFactSource.class);
        }

        @Bean
        MemoryConsolidationRunRepository runs() {
            return mock(MemoryConsolidationRunRepository.class);
        }

        @Bean
        MemoryTombstoneWriter tombstones() {
            return mock(MemoryTombstoneWriter.class);
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
        return ctx.getBeanNamesForType(MemoryConsolidationService.class).length
                + ctx.getBeanNamesForType(MemoryConsolidationSweep.class).length;
    }

    @Test
    void offByDefault() {
        runner.run(ctx -> assertEquals(0, count(ctx)));
    }

    @Test
    void flagOnCreatesServiceAndSweep() {
        runner.withPropertyValues("app.memory.consolidation.enabled=true").run(ctx -> assertEquals(2, count(ctx)));
    }
}
