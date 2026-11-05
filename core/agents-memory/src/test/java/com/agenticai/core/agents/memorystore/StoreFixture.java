package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProvenanceRecord;
import com.agenticai.core.agents.memory.spi.MemoryWriteProposal;
import com.agenticai.core.security.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Hand-wired store pieces over the in-memory storage; no Spring context. */
class StoreFixture {

    final MemoryUnifiedProperties props = new MemoryUnifiedProperties();
    final InMemoryMemoryItemStorage storage = new InMemoryMemoryItemStorage();
    final Clock clock = Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), ZoneOffset.UTC);
    final UUID agent = UUID.randomUUID();
    MemoryEmbedder embedder = text -> vector(text);
    boolean overrideAllows = true;

    final MemoryUnifiedGate gate;
    final StoreMemoryProposalSink sink;
    final MemoryItemReviewService review;
    final MemoryItemStore store;

    StoreFixture() {
        props.setEnabled(true);
        props.setKinds(
                EnumSet.of(MemoryKind.EPISODIC, MemoryKind.PROCEDURAL, MemoryKind.SELF_MODEL));
        StaticListableBeanFactory flags = new StaticListableBeanFactory();
        flags.addBean("o", (MemoryFlagOverride) id -> overrideAllows);
        gate = new MemoryUnifiedGate(props, flags.getBeanProvider(MemoryFlagOverride.class));
        StaticListableBeanFactory emb = new StaticListableBeanFactory();
        emb.addBean("e", (MemoryEmbedder) t -> embedder.embed(t));
        var embProvider = emb.getBeanProvider(MemoryEmbedder.class);
        var noMeters =
                new StaticListableBeanFactory()
                        .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class);
        List<MemoryContentScreen> screens = List.of(new BasicMemoryContentScreen(props));
        sink =
                new StoreMemoryProposalSink(
                        props, gate, storage, embProvider, screens, noMeters, clock);
        review = new MemoryItemReviewService(props, storage, embProvider, screens, clock);
        store = new MemoryItemStore(props, gate, storage, embProvider, clock);
        TenantContext.set(TenantContext.of("tenant_a", "tester", Set.of()));
    }

    void close() {
        TenantContext.clear();
    }

    static float[] vector(String text) {
        float[] v = new float[MemoryEmbedder.DIMENSION];
        v[Math.floorMod(text.hashCode(), v.length)] = 1f;
        v[0] += 0.01f;
        return v;
    }

    MemoryWriteProposal proposal(MemoryKind kind, String text) {
        return new MemoryWriteProposal(
                agent, "user-1", kind, text, MemoryProvenanceRecord.TRUSTED, null, false);
    }

    /** Proposes and human-approves an item; returns its id. */
    UUID active(MemoryKind kind, String text) {
        UUID id = sink.proposeDetailed(proposal(kind, text)).receipt().id();
        if (!review.approve(agent, id, MemoryActor.human("alice")).ok()) {
            throw new IllegalStateException("approve refused");
        }
        return id;
    }
}
