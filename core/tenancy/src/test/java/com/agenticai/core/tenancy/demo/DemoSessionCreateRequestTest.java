package com.agenticai.core.tenancy.demo;

import com.agenticai.core.tenancy.demo.dto.DemoSessionCreateRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Agent Studio asks for a sandbox with no seeded starter agent ({@code blank}); every other caller (the legacy chat
 * sandbox, share links, Telegram) omits it and must keep getting the seeded agent, so absent/false/null all mean "seed".
 */
class DemoSessionCreateRequestTest {

    @Test
    void onlyAnExplicitTrueMeansBlank() {
        assertTrue(new DemoSessionCreateRequest(null, null, null, true).wantsBlank());
        assertFalse(new DemoSessionCreateRequest(null, null, null, false).wantsBlank());
        assertFalse(new DemoSessionCreateRequest(null, null, null, null).wantsBlank());
    }
}
