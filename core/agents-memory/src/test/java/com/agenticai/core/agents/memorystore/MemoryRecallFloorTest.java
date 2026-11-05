package com.agenticai.core.agents.memorystore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.memory.spi.MemoryItemView;
import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProvenanceRecord;
import com.agenticai.core.agents.memory.spi.MemoryRecallRequest;
import com.agenticai.core.agents.memory.spi.MemoryRecallResult;
import com.agenticai.core.agents.memory.spi.MemoryRecallSource;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** {@code app.memory.recall.min-similarity} in the unified composition: unrelated lines out, the self-model line untouched. */
class MemoryRecallFloorTest {

    private final UUID agent = UUID.randomUUID();
    private final MemoryUnifiedProperties props = new MemoryUnifiedProperties();
    private final MemoryRecallSource source = mock(MemoryRecallSource.class);

    private MemoryRecallComposer composer(MemoryRecallProperties relevance) {
        props.setEnabled(true);
        when(source.name()).thenReturn("fake");
        when(source.kinds()).thenReturn(Set.of(MemoryKind.SEMANTIC, MemoryKind.EPISODIC, MemoryKind.SELF_MODEL));
        @SuppressWarnings("unchecked")
        ObjectProvider<MemoryRecallSource> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(i -> Stream.of(source));
        @SuppressWarnings("unchecked")
        ObjectProvider<MemoryFlagOverride> overrides = mock(ObjectProvider.class);
        when(overrides.orderedStream()).thenAnswer(i -> Stream.<MemoryFlagOverride>empty());
        MemoryUnifiedGate gate = new MemoryUnifiedGate(props, overrides);
        return new MemoryRecallComposer(props, gate, provider, relevance);
    }

    private static MemoryItemView item(MemoryKind kind, String text, double score) {
        return new MemoryItemView(UUID.randomUUID(), kind, text, score, MemoryProvenanceRecord.TRUSTED);
    }

    private MemoryRecallResult compose(MemoryRecallProperties relevance, MemoryItemView... items) {
        props.setKinds(Set.of(MemoryKind.EPISODIC, MemoryKind.SELF_MODEL));
        MemoryRecallComposer c = composer(relevance);
        when(source.recall(org.mockito.ArgumentMatchers.any())).thenReturn(new MemoryRecallResult(List.of(items), 0, 0, null));
        return c.compose(new MemoryRecallRequest(agent, "u1", "q", Set.of(), 0, 0));
    }

    @Test
    void aLineBelowTheFloorIsLeftOut() {
        MemoryRecallProperties floor = new MemoryRecallProperties();
        floor.setMinSimilarity(0.30);

        MemoryRecallResult r = compose(floor, item(MemoryKind.EPISODIC, "close", 0.31), item(MemoryKind.SEMANTIC, "far", 0.29));

        assertThat(r.items()).extracting(MemoryItemView::text).containsExactly("close");
    }

    @Test
    void theSelfModelLineIsNotMatchedToTheMessageSoTheFloorDoesNotApply() {
        MemoryRecallProperties floor = new MemoryRecallProperties();
        floor.setMinSimilarity(0.90);

        MemoryRecallResult r = compose(floor, item(MemoryKind.SELF_MODEL, "good at invoices", 0.5), item(MemoryKind.EPISODIC, "far", 0.5));

        assertThat(r.items()).extracting(MemoryItemView::text).containsExactly("good at invoices");
    }

    @Test
    void noFloorAndTheOldConstructorKeepEveryLine() {
        MemoryItemView far = item(MemoryKind.EPISODIC, "far", 0.01);

        assertThat(compose(MemoryRecallProperties.off(), far).items()).hasSize(1);
        assertThat(compose(null, far).items()).hasSize(1);
    }

    @Test
    void shippedDefaultsAreTheDocumentedOnesAndOffMeansOff() {
        MemoryRecallProperties defaults = new MemoryRecallProperties();
        assertThat(defaults.getMinSimilarity()).isEqualTo(0.20);
        assertThat(defaults.getMaxItems()).isEqualTo(3);
        assertThat(defaults.passes(0.19)).isFalse();
        assertThat(defaults.passes(0.20)).isTrue();

        MemoryRecallProperties off = MemoryRecallProperties.off();
        assertThat(off.passes(0.0)).isTrue();
        assertThat(off.getMaxItems()).isZero();
    }
}
