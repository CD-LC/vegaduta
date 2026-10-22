package com.agenticai.core.security.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class LogSafeTest {

    @Test
    void anOrdinaryIdentifierIsLoggedUnchanged() {
        UUID id = UUID.randomUUID();
        assertThat(LogSafe.clean(id)).isEqualTo(id.toString());
        assertThat(LogSafe.clean("t_abc123")).isEqualTo("t_abc123");
        assertThat(LogSafe.clean("subscription.charged")).isEqualTo("subscription.charged");
        assertThat(LogSafe.clean(42)).isEqualTo("42");
        assertThat(LogSafe.clean("tenant: A&B / ünïcode")).isEqualTo("tenant: A&B / ünïcode");
    }

    @Test
    void nullIsLoggedAsNullLikeSlf4jDoes() {
        assertThat(LogSafe.clean(null)).isEqualTo("null");
    }

    @Test
    void lineBreaksCannotForgeASecondLogLine() {
        String forged = "task-1\n2026-01-01 INFO  admin logged in";
        assertThat(LogSafe.clean(forged))
                .doesNotContain("\n")
                .isEqualTo("task-1_2026-01-01 INFO  admin logged in");
        assertThat(LogSafe.clean("a\r\nb\rc")).isEqualTo("a__b_c");
    }

    @Test
    void otherControlCharactersAreReplacedToo() {
        assertThat(LogSafe.clean("a\u001b[31mred\u0000b\u007f")).isEqualTo("a_[31mred_b_");
    }

    @Test
    void anObjectIsLoggedThroughItsToString() {
        Object o =
                new Object() {
                    @Override
                    public String toString() {
                        return "line1\nline2";
                    }
                };
        assertThat(LogSafe.clean(o)).isEqualTo("line1_line2");
    }
}
