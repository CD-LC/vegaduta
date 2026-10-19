package com.agenticai.core.agents.a2a;

import com.agenticai.core.security.net.SsrfGuard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/platform/security/outbound-policy} - a read-only view of the operator-set
 * outbound limits the platform-admin Safety page shows: the egress allow-list and private-host
 * exceptions ({@link SsrfGuard}), and the A2A client and server settings.
 *
 * <p>Read-only on purpose: these are environment settings (see docs/configuration.md); changing
 * them from a browser would let a stolen admin session widen egress. Authorization is the blanket
 * {@code /api/platform/**} rule ({@code ROLE_platform-admin}) in {@code SecurityConfig}. No secret
 * is returned: host lists and numbers only.
 */
@RestController
@RequestMapping("/api/platform/security")
public class OutboundPolicyController {

    private final SsrfGuard ssrfGuard;
    private final A2aProperties a2a;

    public OutboundPolicyController(SsrfGuard ssrfGuard, A2aProperties a2a) {
        this.ssrfGuard = ssrfGuard;
        this.a2a = a2a;
    }

    @GetMapping("/outbound-policy")
    public ResponseEntity<Map<String, Object>> outboundPolicy() {
        Map<String, Object> egress = new LinkedHashMap<>();
        List<String> allow = ssrfGuard.egressAllowList();
        egress.put("restricted", !allow.isEmpty());
        egress.put("allowList", allow);
        egress.put("privateHostExceptions", ssrfGuard.allowedPrivateHosts());

        A2aProperties.Client client = a2a.getClient();
        Map<String, Object> a2aClient = new LinkedHashMap<>();
        a2aClient.put("enabled", client.isEnabled());
        a2aClient.put("allowedHosts", client.getAllowedHosts() == null ? List.of() : client.getAllowedHosts());
        a2aClient.put("requireSignedCard", client.isRequireSignedCard());
        a2aClient.put("maxCallsPerTurn", client.getMaxCallsPerTurn());

        Map<String, Object> a2aServer = new LinkedHashMap<>();
        a2aServer.put("enabled", a2a.isEnabled());
        a2aServer.put("bindings", List.of("JSONRPC", "HTTP+JSON"));
        a2aServer.put("jsonRpcPath", "/api/a2a");
        a2aServer.put("restPath", "/api/a2a/rest");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("egress", egress);
        body.put("a2aClient", a2aClient);
        body.put("a2aServer", a2aServer);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
