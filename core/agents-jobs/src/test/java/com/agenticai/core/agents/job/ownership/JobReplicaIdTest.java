package com.agenticai.core.agents.job.ownership;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JobReplicaIdTest {

    @Test
    void usesTheReplicaNameRevisionPidAndRandomSuffix() {
        assertThat(JobReplicaId.compute("core--abc-123", "host", "core--abc", "1", "deadbeef"))
            .isEqualTo("core--abc-123/core--abc:1:deadbeef");
    }

    @Test
    void fallsBackToTheHostnameThenUnknown() {
        assertThat(JobReplicaId.compute(null, "host", null, "7", "aa")).isEqualTo("host:7:aa");
        assertThat(JobReplicaId.compute(" ", " ", "", "7", "aa")).isEqualTo("unknown:7:aa");
    }

    @Test
    void twoIncarnationsOnTheSameHostAndPidDiffer() {
        assertThat(JobReplicaId.compute(null, "host", null, "1", "aaaaaaaa"))
            .isNotEqualTo(JobReplicaId.compute(null, "host", null, "1", "bbbbbbbb"));
    }

    @Test
    void neverExceedsTheColumnWidthAndKeepsTheSuffix() {
        String longHost = "h".repeat(300);
        String id = JobReplicaId.compute(null, longHost, "rev", "1", "cafebabe");
        assertThat(id.length()).isLessThanOrEqualTo(128);
        assertThat(id).endsWith(":1:cafebabe");
    }

    @Test
    void currentIsStableWithinOneProcess() {
        assertThat(JobReplicaId.current()).isEqualTo(JobReplicaId.current());
        assertThat(JobReplicaId.current().length()).isLessThanOrEqualTo(128);
    }
}
