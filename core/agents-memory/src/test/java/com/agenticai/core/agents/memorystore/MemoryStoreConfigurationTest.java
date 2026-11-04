package com.agenticai.core.agents.memorystore;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.memory.spi.MemoryProposalSink;
import com.agenticai.core.agents.memory.spi.MemoryRecallRequest;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MemoryStoreConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(MemoryStoreConfiguration.class);

    @Test
    void wiresDarkByDefault() {
        runner.run(
                ctx -> {
                    assertThat(ctx.getBean(MemoryUnifiedProperties.class).isEnabled()).isFalse();
                    assertThat(ctx.getBean(MemoryUnifiedProperties.class).getKinds()).isEmpty();
                    assertThat(ctx.getBean(MemoryUnifiedGate.class).enabled(UUID.randomUUID())).isFalse();
                    assertThat(ctx.getBean(MemoryProposalSink.class)).isInstanceOf(DisabledMemoryProposalSink.class);
                    assertThat(
                                    ctx.getBean(MemoryRecallComposer.class)
                                            .compose(new MemoryRecallRequest(UUID.randomUUID(), "u", "q", Set.of(), 0, 0))
                                            .items())
                            .isEmpty();
                });
    }

    @Test
    void theStoreBackedSinkReplacesThePlaceholderOnlyWhenThePlatformFlagIsOn() {
        runner.withPropertyValues("app.memory.unified.enabled=true")
                .run(
                        ctx -> {
                            assertThat(ctx.getBean(MemoryProposalSink.class)).isInstanceOf(StoreMemoryProposalSink.class);
                            assertThat(ctx.getBeansOfType(MemoryProposalSink.class)).hasSize(1);
                        });
        runner.run(ctx -> assertThat(ctx.getBeansOfType(MemoryProposalSink.class)).hasSize(1));
    }

    @Test
    void theStoreStartsWithoutADatabaseAndRecallsNothing() {
        runner.run(
                ctx -> {
                    assertThat(ctx.getBean(MemoryItemStore.class).kinds())
                            .doesNotContain(com.agenticai.core.agents.memory.spi.MemoryKind.SEMANTIC);
                    assertThat(ctx).hasSingleBean(MemoryItemReviewService.class);
                    assertThat(
                                    ctx.getBean(MemoryItemStore.class)
                                            .recall(new MemoryRecallRequest(UUID.randomUUID(), "u", "q", Set.of(), 1, 1))
                                            .items())
                            .isEmpty();
                });
    }

    @Test
    void platformFlagAloneTurnsTheGateOn() {
        runner.withPropertyValues("app.memory.unified.enabled=true")
                .run(ctx -> assertThat(ctx.getBean(MemoryUnifiedGate.class).enabled(UUID.randomUUID())).isTrue());
    }

    @Test
    void aTenantOrAgentLevelCanOnlyTurnItOff() {
        runner.withPropertyValues("app.memory.unified.enabled=true")
                .withBean(MemoryFlagOverride.class, () -> agentId -> false)
                .run(ctx -> assertThat(ctx.getBean(MemoryUnifiedGate.class).enabled(UUID.randomUUID())).isFalse());
        runner.withBean(MemoryFlagOverride.class, () -> agentId -> true)
                .run(ctx -> assertThat(ctx.getBean(MemoryUnifiedGate.class).enabled(UUID.randomUUID())).isFalse());
    }
}
