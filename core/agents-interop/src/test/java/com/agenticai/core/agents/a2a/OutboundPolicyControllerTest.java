package com.agenticai.core.agents.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.security.net.SsrfGuard;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OutboundPolicyControllerTest {

    @SuppressWarnings("unchecked")
    @Test
    void reportsTheEffectiveOperatorSettings() {
        A2aProperties props = new A2aProperties();
        props.getClient().setEnabled(true);
        props.getClient().setAllowedHosts(new java.util.ArrayList<>(List.of("agents.example.com")));
        props.getClient().setMaxCallsPerTurn(3);
        SsrfGuard guard = SsrfGuard.of(Set.of("ollama:11434"), Set.of("b.example.com", "*.a.io"), InetAddress::getAllByName);

        Map<String, Object> body = new OutboundPolicyController(guard, props).outboundPolicy().getBody();

        Map<String, Object> egress = (Map<String, Object>) body.get("egress");
        assertEquals(true, egress.get("restricted"));
        assertEquals(List.of("*.a.io", "b.example.com"), egress.get("allowList"));
        assertEquals(List.of("ollama:11434"), egress.get("privateHostExceptions"));
        Map<String, Object> client = (Map<String, Object>) body.get("a2aClient");
        assertEquals(3, client.get("maxCallsPerTurn"));
        assertEquals(true, client.get("enabled"));
        assertTrue(((Map<String, Object>) body.get("a2aServer")).get("bindings").toString().contains("HTTP+JSON"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void anUnrestrictedInstallSaysSo() {
        Map<String, Object> body =
            new OutboundPolicyController(SsrfGuard.strict(), new A2aProperties()).outboundPolicy().getBody();
        assertFalse((Boolean) ((Map<String, Object>) body.get("egress")).get("restricted"));
    }
}
