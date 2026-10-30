package com.agenticai.core.agents.tools.code;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link ToolSandboxProperties} is the {@code tool-sandbox.*} config block. These pin the shipped
 * defaults - off, pointing at the compose service name - since the size/time/memory caps here are
 * the platform ceilings {@code CustomCodeToolService} clamps tenant requests into.
 */
class ToolSandboxPropertiesTest {

    @Test
    void defaultsAreOffWithTheComposeUrlAndPlatformCeilings() {
        ToolSandboxProperties p = new ToolSandboxProperties();

        assertFalse(p.isEnabled());
        assertEquals("http://tool-sandbox:8095", p.getBaseUrl());
        assertEquals("", p.getInternalToken());
        assertEquals(5000, p.getConnectTimeoutMillis());
        assertEquals(30000, p.getRequestTimeoutMillis());
        assertFalse(p.isE2bExecution());
        assertEquals(65536, p.getMaxSourceBytes());
        assertEquals(5000, p.getDefaultTimeoutMs());
        assertEquals(15000, p.getMaxTimeoutMs());
        assertEquals(128, p.getDefaultMemoryMb());
        assertEquals(512, p.getMaxMemoryMb());
        assertEquals(25, p.getMaxToolsPerTenant());
    }

    @Test
    void theRequestTimeoutComfortablyExceedsThePerProgramCap() {
        // A request covers compile + transport on top of the sandbox's own wall clock; a config
        // that inverted this would fail every slow program at the HTTP layer first.
        ToolSandboxProperties p = new ToolSandboxProperties();
        assertTrue(p.getRequestTimeoutMillis() > p.getMaxTimeoutMs());
    }

    @Test
    void everySetterRoundTrips() {
        ToolSandboxProperties p = new ToolSandboxProperties();

        p.setEnabled(true);
        p.setBaseUrl("http://sandbox.internal:9000");
        p.setInternalToken("secret");
        p.setConnectTimeoutMillis(1000);
        p.setRequestTimeoutMillis(60000);
        p.setE2bExecution(true);
        p.setMaxSourceBytes(32768);
        p.setDefaultTimeoutMs(2000);
        p.setMaxTimeoutMs(10000);
        p.setDefaultMemoryMb(64);
        p.setMaxMemoryMb(256);
        p.setMaxToolsPerTenant(5);

        assertTrue(p.isEnabled());
        assertEquals("http://sandbox.internal:9000", p.getBaseUrl());
        assertEquals("secret", p.getInternalToken());
        assertEquals(1000, p.getConnectTimeoutMillis());
        assertEquals(60000, p.getRequestTimeoutMillis());
        assertTrue(p.isE2bExecution());
        assertEquals(32768, p.getMaxSourceBytes());
        assertEquals(2000, p.getDefaultTimeoutMs());
        assertEquals(10000, p.getMaxTimeoutMs());
        assertEquals(64, p.getDefaultMemoryMb());
        assertEquals(256, p.getMaxMemoryMb());
        assertEquals(5, p.getMaxToolsPerTenant());
    }
}
