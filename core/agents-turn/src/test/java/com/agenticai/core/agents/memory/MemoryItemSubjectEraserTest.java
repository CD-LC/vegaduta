package com.agenticai.core.agents.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.memorystore.MemoryItemErasure;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The DPDP erasure hook for {@code memory_item}: delegates to the store's erasure and tolerates its
 * absence.
 */
@SuppressWarnings("unchecked")
class MemoryItemSubjectEraserTest {

    private final MemoryItemErasure erasure = mock(MemoryItemErasure.class);
    private final ObjectProvider<MemoryItemErasure> provider = mock(ObjectProvider.class);

    @Test
    void delegatesTheSubjectAndReturnsTheErasedRowCount() {
        when(provider.getIfAvailable()).thenReturn(erasure);
        when(erasure.eraseSubject("alice")).thenReturn(3);
        MemoryItemSubjectEraser eraser = new MemoryItemSubjectEraser(provider);

        assertThat(eraser.store()).isEqualTo("memory_items");
        assertThat(eraser.eraseSubject("alice")).isEqualTo(3);
        verify(erasure).eraseSubject("alice");
    }

    @Test
    void withoutTheStoreItIsANoOp() {
        when(provider.getIfAvailable()).thenReturn(null);
        assertThat(new MemoryItemSubjectEraser(provider).eraseSubject("alice")).isZero();
    }
}
