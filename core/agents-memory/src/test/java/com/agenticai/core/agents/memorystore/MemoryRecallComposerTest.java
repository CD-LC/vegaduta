package com.agenticai.core.agents.memorystore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

class MemoryRecallComposerTest {

    private final UUID agent = UUID.randomUUID();
    private final MemoryUnifiedProperties props = new MemoryUnifiedProperties();
    private final MemoryRecallSource source = mock(MemoryRecallSource.class);
    private MemoryFlagOverride override = a -> true;

    private MemoryRecallComposer composer() {
        when(source.name()).thenReturn("fake");
        when(source.kinds()).thenReturn(Set.of(MemoryKind.SEMANTIC, MemoryKind.EPISODIC));
        MemoryUnifiedGate gate = new MemoryUnifiedGate(props, provider(override));
        return new MemoryRecallComposer(props, gate, provider(source));
    }

    @SafeVarargs
    private static <T> ObjectProvider<T> provider(T... items) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.orderedStream()).thenAnswer(i -> Stream.of(items));
        return p;
    }

    private static MemoryItemView item(MemoryKind kind, String text, double score) {
        return new MemoryItemView(
                UUID.randomUUID(), kind, text, score, MemoryProvenanceRecord.TRUSTED);
    }

    private MemoryRecallRequest request() {
        return new MemoryRecallRequest(agent, "u1", "q", Set.of(), 0, 0);
    }

    @Test
    void flagOffIsANoOpAndNoSourceIsCalled() {
        MemoryRecallComposer c = composer();
        MemoryRecallResult r = c.compose(request());
        assertThat(r.items()).isEmpty();
        assertThat(r.degradedReason()).isEqualTo(MemoryRecallComposer.DISABLED);
        verify(source, never()).recall(any());
    }

    @Test
    void anyLowerLevelVetoSwitchesItOffEvenWhenPlatformIsOn() {
        props.setEnabled(true);
        override = a -> false;
        MemoryRecallComposer c = composer();
        assertThat(c.compose(request()).items()).isEmpty();
        verify(source, never()).recall(any());
    }

    @Test
    void aThrowingLevelFailsClosed() {
        props.setEnabled(true);
        override =
                a -> {
                    throw new IllegalStateException("settings down");
                };
        MemoryRecallComposer c = composer();
        assertThat(c.compose(request()).degradedReason()).isEqualTo(MemoryRecallComposer.DISABLED);
    }

    @Test
    void mergesSortsByScoreAndDedupesById() {
        props.setEnabled(true);
        MemoryItemView low = item(MemoryKind.SEMANTIC, "low", 0.2);
        MemoryItemView high = item(MemoryKind.SEMANTIC, "high", 0.9);
        when(source.recall(any()))
                .thenReturn(new MemoryRecallResult(List.of(low, high, high), 0, 0, null));
        MemoryRecallComposer c = composer();
        MemoryRecallResult r = c.compose(request());
        assertThat(r.items()).extracting(MemoryItemView::text).containsExactly("high", "low");
    }

    @Test
    void mergesAcrossSources() {
        props.setEnabled(true);
        MemoryRecallSource other = mock(MemoryRecallSource.class);
        when(other.name()).thenReturn("other");
        when(other.kinds()).thenReturn(Set.of(MemoryKind.SEMANTIC));
        when(other.recall(any()))
                .thenReturn(
                        new MemoryRecallResult(
                                List.of(item(MemoryKind.SEMANTIC, "b", 0.5)), 0, 0, null));
        when(source.name()).thenReturn("fake");
        when(source.kinds()).thenReturn(Set.of(MemoryKind.SEMANTIC));
        when(source.recall(any()))
                .thenReturn(
                        new MemoryRecallResult(
                                List.of(item(MemoryKind.SEMANTIC, "a", 0.7)), 0, 0, null));
        MemoryUnifiedGate gate = new MemoryUnifiedGate(props, provider(override));
        MemoryRecallComposer c = new MemoryRecallComposer(props, gate, provider(source, other));
        assertThat(c.compose(request()).items())
                .extracting(MemoryItemView::text)
                .containsExactly("a", "b");
    }

    @Test
    void totalBudgetTruncatesAndCountsDropped() {
        props.setEnabled(true);
        props.setTotalTokens(10);
        String forty = "x".repeat(40); // 10 tokens
        when(source.recall(any()))
                .thenReturn(
                        new MemoryRecallResult(
                                List.of(
                                        item(MemoryKind.SEMANTIC, forty, 0.9),
                                        item(MemoryKind.SEMANTIC, "second", 0.8),
                                        item(MemoryKind.SEMANTIC, "third", 0.7)),
                                0,
                                0,
                                null));
        MemoryRecallResult r = composer().compose(request());
        assertThat(r.items()).hasSize(1);
        assertThat(r.tokensUsed()).isLessThanOrEqualTo(10);
        assertThat(r.dropped()).isEqualTo(2);
    }

    @Test
    void defaultBudgetIs800TokensAndMaxEightItems() {
        assertThat(props.getTotalTokens()).isEqualTo(800);
        assertThat(props.getMaxItems()).isEqualTo(8);
        props.setEnabled(true);
        List<MemoryItemView> many = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(item(MemoryKind.SEMANTIC, "fact " + i, 1.0 - i / 100.0));
        }
        when(source.recall(any())).thenReturn(new MemoryRecallResult(many, 0, 0, null));
        MemoryRecallResult r = composer().compose(request());
        assertThat(r.items()).hasSize(8);
        assertThat(r.dropped()).isEqualTo(12);
        assertThat(r.tokensUsed()).isLessThanOrEqualTo(800);
    }

    @Test
    void overlongItemIsCutAtASentenceOrDropped() {
        props.setEnabled(true);
        props.setPerItemTokens(10);
        String sentenceThenLong = "Short first sentence. " + "y".repeat(400);
        String noBoundary = "z".repeat(400);
        when(source.recall(any()))
                .thenReturn(
                        new MemoryRecallResult(
                                List.of(
                                        item(MemoryKind.SEMANTIC, sentenceThenLong, 0.9),
                                        item(MemoryKind.SEMANTIC, noBoundary, 0.8)),
                                0,
                                0,
                                null));
        MemoryRecallResult r = composer().compose(request());
        assertThat(r.items())
                .extracting(MemoryItemView::text)
                .containsExactly("Short first sentence.");
        assertThat(r.dropped()).isEqualTo(1);
    }

    @Test
    void nonSemanticKindsStayOutUntilThePlatformListsThem() {
        props.setEnabled(true);
        MemoryItemView episodic = item(MemoryKind.EPISODIC, "yesterday", 0.9);
        MemoryItemView semantic = item(MemoryKind.SEMANTIC, "likes INR", 0.5);
        when(source.recall(any()))
                .thenReturn(new MemoryRecallResult(List.of(episodic, semantic), 0, 0, null));
        assertThat(composer().compose(request()).items())
                .extracting(MemoryItemView::kind)
                .containsExactly(MemoryKind.SEMANTIC);

        props.setKinds(Set.of(MemoryKind.EPISODIC));
        assertThat(composer().compose(request()).items()).hasSize(2);
    }

    @Test
    void aFailingSourceIsSkippedAndReported() {
        props.setEnabled(true);
        when(source.recall(any())).thenThrow(new IllegalStateException("db"));
        MemoryRecallResult r = composer().compose(request());
        assertThat(r.items()).isEmpty();
        assertThat(r.degradedReason()).isEqualTo("fake:error");
    }
}
