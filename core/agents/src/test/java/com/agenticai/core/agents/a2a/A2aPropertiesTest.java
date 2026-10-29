package com.agenticai.core.agents.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The {@code app.a2a.*} block must boot a working server on a fresh deployment with nothing set, so
 * these pin the defaults every unset property resolves to - plus the one defensive bit of logic in
 * the file ({@code allowedHosts == null} collapsing to an empty list, since an empty allow-list is
 * the deny-all state the client ships with).
 */
class A2aPropertiesTest {

    @Test
    void serverDefaultsProduceAWorkingInMemoryServer() {
        A2aProperties p = new A2aProperties();

        assertTrue(p.isEnabled());
        assertEquals("", p.getPublicBaseUrl());
        assertEquals("", p.getVersion());
        assertEquals(Duration.ofHours(24), p.getTaskTtl());
        assertEquals(Duration.ofMinutes(5), p.getBlockingTimeout());
        assertEquals(10_000, p.getMaxTasks());
        assertEquals(32, p.getMaxConcurrentAsyncTasks());
        assertEquals("memory", p.getTaskStore());
        assertEquals(Duration.ofSeconds(10), p.getHeartbeatInterval());
        assertEquals(Duration.ofSeconds(60), p.getHeartbeatStaleAfter());
        assertFalse(p.isRequirePinnedKey());
    }

    @Test
    void signingDefaultsOnWithAnEphemeralKey() {
        A2aProperties.Signing signing = new A2aProperties().getSigning();

        assertNotNull(signing);
        assertTrue(signing.isEnabled());
        assertEquals("", signing.getPrivateKeyPem());
        assertEquals("", signing.getPublicKeyPem());
        assertEquals("", signing.getKeyId());
    }

    @Test
    void clientDefaultsOffWithNothingReachable() {
        A2aProperties.Client client = new A2aProperties().getClient();

        assertNotNull(client);
        assertFalse(client.isEnabled());
        assertTrue(client.getAllowedHosts().isEmpty());
        assertTrue(client.isRequireSignedCard());
        assertFalse(client.isAllowInsecureLoopback());
        assertEquals(Duration.ofSeconds(5), client.getConnectTimeout());
        assertEquals(Duration.ofSeconds(30), client.getRequestTimeout());
        assertEquals(Duration.ofSeconds(120), client.getMaxWait());
        assertEquals(Duration.ofSeconds(2), client.getPollInterval());
        assertEquals(1_048_576, client.getMaxResponseBytes());
        assertEquals(20_000, client.getMaxResultChars());
        assertEquals(5, client.getMaxCallsPerTurn());
    }

    @Test
    void aNullAllowedHostsListMeansNothingAllowedNotAnError() {
        A2aProperties.Client client = new A2aProperties.Client();
        client.setAllowedHosts(null);
        assertTrue(client.getAllowedHosts().isEmpty());

        client.setAllowedHosts(List.of("agents.example.com", "*.example.org"));
        assertEquals(List.of("agents.example.com", "*.example.org"), client.getAllowedHosts());
    }

    @Test
    void everySetterRoundTrips() {
        A2aProperties p = new A2aProperties();
        p.setEnabled(false);
        p.setPublicBaseUrl("https://api.example.com");
        p.setVersion("1.2.3");
        p.setTaskTtl(Duration.ofHours(1));
        p.setBlockingTimeout(Duration.ofSeconds(30));
        p.setMaxTasks(50);
        p.setMaxConcurrentAsyncTasks(4);
        p.setTaskStore("redis");
        p.setHeartbeatInterval(Duration.ofSeconds(5));
        p.setHeartbeatStaleAfter(Duration.ofSeconds(30));
        p.setRequirePinnedKey(true);

        assertFalse(p.isEnabled());
        assertEquals("https://api.example.com", p.getPublicBaseUrl());
        assertEquals("1.2.3", p.getVersion());
        assertEquals(Duration.ofHours(1), p.getTaskTtl());
        assertEquals(Duration.ofSeconds(30), p.getBlockingTimeout());
        assertEquals(50, p.getMaxTasks());
        assertEquals(4, p.getMaxConcurrentAsyncTasks());
        assertEquals("redis", p.getTaskStore());
        assertEquals(Duration.ofSeconds(5), p.getHeartbeatInterval());
        assertEquals(Duration.ofSeconds(30), p.getHeartbeatStaleAfter());
        assertTrue(p.isRequirePinnedKey());

        A2aProperties.Signing signing = new A2aProperties.Signing();
        signing.setEnabled(false);
        signing.setPrivateKeyPem("PRIVATE");
        signing.setPublicKeyPem("PUBLIC");
        signing.setKeyId("kid-1");
        p.setSigning(signing);
        assertFalse(p.getSigning().isEnabled());
        assertEquals("PRIVATE", p.getSigning().getPrivateKeyPem());
        assertEquals("PUBLIC", p.getSigning().getPublicKeyPem());
        assertEquals("kid-1", p.getSigning().getKeyId());

        A2aProperties.Client client = new A2aProperties.Client();
        client.setEnabled(true);
        client.setRequireSignedCard(false);
        client.setAllowInsecureLoopback(true);
        client.setConnectTimeout(Duration.ofSeconds(1));
        client.setRequestTimeout(Duration.ofSeconds(2));
        client.setMaxWait(Duration.ofSeconds(3));
        client.setPollInterval(Duration.ofMillis(500));
        client.setMaxResponseBytes(1024);
        client.setMaxResultChars(256);
        client.setMaxCallsPerTurn(2);
        p.setClient(client);
        assertTrue(p.getClient().isEnabled());
        assertFalse(p.getClient().isRequireSignedCard());
        assertTrue(p.getClient().isAllowInsecureLoopback());
        assertEquals(Duration.ofSeconds(1), p.getClient().getConnectTimeout());
        assertEquals(Duration.ofSeconds(2), p.getClient().getRequestTimeout());
        assertEquals(Duration.ofSeconds(3), p.getClient().getMaxWait());
        assertEquals(Duration.ofMillis(500), p.getClient().getPollInterval());
        assertEquals(1024, p.getClient().getMaxResponseBytes());
        assertEquals(256, p.getClient().getMaxResultChars());
        assertEquals(2, p.getClient().getMaxCallsPerTurn());
    }
}
