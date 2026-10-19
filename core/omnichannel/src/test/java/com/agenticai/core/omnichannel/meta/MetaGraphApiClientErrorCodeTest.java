package com.agenticai.core.omnichannel.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MetaGraphApiClientErrorCodeTest {

    @Test
    void parsesMetaErrorCode() {
        assertEquals(
                131049,
                MetaGraphApiClient.metaErrorCode(
                        "{\"error\":{\"message\":\"x\",\"type\":\"OAuthException\",\"code\":131049}}"));
    }

    @Test
    void missingOrBrokenBodyIsZero() {
        assertEquals(0, MetaGraphApiClient.metaErrorCode("{}"));
        assertEquals(0, MetaGraphApiClient.metaErrorCode("<html>"));
        assertEquals(0, MetaGraphApiClient.metaErrorCode(null));
    }
}
