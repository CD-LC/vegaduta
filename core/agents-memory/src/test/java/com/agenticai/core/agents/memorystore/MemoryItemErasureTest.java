package com.agenticai.core.agents.memorystore;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** {@link MemoryItemErasure}: erases a subject through the storage, with no feature flag involved. */
class MemoryItemErasureTest {

    private final StoreFixture f = new StoreFixture();

    @AfterEach
    void close() {
        f.close();
    }

    @Test
    void erasesTheSubjectsItemsAndCountsThemEvenWhenTheFeatureIsSwitchedOff() {
        f.active(com.agenticai.core.agents.memory.spi.MemoryKind.EPISODIC, "an episode about user-1");
        f.props.setEnabled(false);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("storage", f.storage);
        MemoryItemErasure erasure = new MemoryItemErasure(beans.getBeanProvider(MemoryItemStorage.class));

        assertThat(erasure.eraseSubject("user-1")).isEqualTo(1);
        assertThat(f.storage.listForSubject("user-1", 10)).isEmpty();
        assertThat(erasure.eraseSubject("user-1")).isZero();
        assertThat(erasure.eraseSubject(" ")).isZero();
    }

    @Test
    void noStorageIsANoOp() {
        MemoryItemErasure erasure = new MemoryItemErasure(
                new StaticListableBeanFactory().getBeanProvider(MemoryItemStorage.class));
        assertThat(erasure.eraseSubject("user-1")).isZero();
    }
}
