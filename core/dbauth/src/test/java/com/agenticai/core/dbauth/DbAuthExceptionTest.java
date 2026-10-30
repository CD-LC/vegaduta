package com.agenticai.core.dbauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Trivial but pinned: {@link DbAuthException} is the one type every token source throws, so its
 * message/cause shape and RuntimeException-ness are part of the module's contract.
 */
class DbAuthExceptionTest {

    @Test
    void carriesMessageAndCause() {
        RuntimeException cause = new RuntimeException("chain exhausted");
        DbAuthException e = new DbAuthException("could not fetch token", cause);

        assertThat(e.getMessage()).isEqualTo("could not fetch token");
        assertThat(e.getCause()).isSameAs(cause);
        assertThat(e).isInstanceOf(RuntimeException.class);
    }
}
