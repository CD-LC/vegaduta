package com.agenticai.core.brief;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The plain persistence holders: what they store, and the composite binding key's equality. */
class BriefEntitiesTest {

    private static final UUID BRIEF = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Test
    void aTrustEventKeepsEverythingItWasGiven() {
        BriefTrustEventEntity e =
                new BriefTrustEventEntity(
                        BRIEF,
                        "UNDER_WATCH",
                        "TRUSTED",
                        "PROMOTED",
                        "[{\"key\":\"x\"}]",
                        4,
                        "alice");

        assertThat(e.getId()).isNotNull();
        assertThat(e.getBriefId()).isEqualTo(BRIEF);
        assertThat(e.getFromRung()).isEqualTo("UNDER_WATCH");
        assertThat(e.getToRung()).isEqualTo("TRUSTED");
        assertThat(e.getKind()).isEqualTo("PROMOTED");
        assertThat(e.getEvidenceJson()).contains("\"key\"");
        assertThat(e.getVersion()).isEqualTo(4);
        assertThat(e.getActor()).isEqualTo("alice");
        assertThat(e.getCreatedAt()).isNotNull();
    }

    @Test
    void twoTrustEventsNeverShareAnId() {
        BriefTrustEventEntity a =
                new BriefTrustEventEntity(BRIEF, null, null, "PROPOSED", null, null, null);
        BriefTrustEventEntity b =
                new BriefTrustEventEntity(BRIEF, null, null, "PROPOSED", null, null, null);
        assertThat(a.getId()).isNotEqualTo(b.getId());
    }

    @Test
    void aBindingExposesItsFourFields() {
        BriefBindingEntity binding = new BriefBindingEntity(BRIEF, "a1", "AGENT", "agent-1");

        assertThat(binding.getBriefId()).isEqualTo(BRIEF);
        assertThat(binding.getRef()).isEqualTo("a1");
        assertThat(binding.getKind()).isEqualTo("AGENT");
        assertThat(binding.getEntityId()).isEqualTo("agent-1");
    }

    @Test
    void theBindingKeyIsEqualOnlyForTheSameBriefAndRef() {
        BriefBindingEntity.Key key = new BriefBindingEntity.Key(BRIEF, "a1");

        assertThat(key)
                .isEqualTo(new BriefBindingEntity.Key(BRIEF, "a1"))
                .hasSameHashCodeAs(new BriefBindingEntity.Key(BRIEF, "a1"))
                .isNotEqualTo(new BriefBindingEntity.Key(BRIEF, "a2"))
                .isNotEqualTo(new BriefBindingEntity.Key(UUID.randomUUID(), "a1"))
                .isNotEqualTo("not a key")
                .isNotEqualTo(new BriefBindingEntity.Key());
    }
}
