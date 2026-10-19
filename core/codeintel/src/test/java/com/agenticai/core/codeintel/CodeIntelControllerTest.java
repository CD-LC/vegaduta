package com.agenticai.core.codeintel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.approval.HumanApprovalGateService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link CodeIntelController} - the WS3 backbone that routes
 * the web console through core/approval. Verifies the two things this
 * controller exists to guarantee, without a full Spring/HTTP stack:
 * <ol>
 *   <li>a console-initiated apply/run goes through {@link
 *       HumanApprovalGateService#evaluate} with the SAME action type +
 *       {@code adminOnly} payload an agent tool call uses (so it lands on the
 *       one central Approvals screen), and</li>
 *   <li>the two service-reaching, non-request actions (resolving code-intel's
 *       own approvals, and the autonomous SDLC agent) are refused for a
 *       non-admin and allowed for a tenant-admin - since code-intel trusts
 *       core's internal token as admin, this controller is the only place
 *       that distinction is enforced for a browser user.</li>
 * </ol>
 */
class CodeIntelControllerTest {

    private static CodeIntelProperties props() {
        CodeIntelProperties p = new CodeIntelProperties();
        p.setEnabled(true);
        p.setBaseUrl("http://localhost:8090");
        p.setInternalToken("t");
        return p;
    }

    /** Records the last delegated call instead of performing HTTP. Overrides the
     *  package-private {@code *Result()} methods (not the public String ones) -
     *  those are what {@link CodeIntelController} actually calls as of the
     *  2026-08-14 status-code-propagation fix; see {@link CodeIntelClient.Result}'s
     *  javadoc. */
    static class CapturingClient extends CodeIntelClient {
        final AtomicReference<String> last = new AtomicReference<>();

        CapturingClient() {
            super(RestClient.builder(), props());
        }

        @Override
        Result listReposResult() {
            last.set("listRepos");
            return new Result("[]", 200);
        }

        @Override
        Result resolveApprovalResult(String approvalId, boolean approved) {
            last.set("resolve:" + approvalId + ":" + approved);
            return new Result("{\"status\":\"approved\"}", 200);
        }

        @Override
        Result agentResolveResult(String repoId, String jsonBody) {
            last.set("agentResolve:" + repoId);
            return new Result("{\"status\":\"resolved\"}", 200);
        }
    }

    /** Fake gate that records the evaluate() args and returns a fixed pending. */
    static class CapturingGate extends HumanApprovalGateService {
        final AtomicReference<String> actionType = new AtomicReference<>();
        final AtomicReference<String> payload = new AtomicReference<>();
        private final UUID pendingId = UUID.randomUUID();

        CapturingGate() {
            super(null, null, null, null, null, null, 0, 0, 0);
        }

        @Override
        public ApprovalEvaluation evaluate(String actionType, String summary, String payloadJson, String requestedBy) {
            this.actionType.set(actionType);
            this.payload.set(payloadJson);
            return ApprovalEvaluation.pending(pendingId);
        }

        UUID pendingId() {
            return pendingId;
        }
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken(
            "admin", "x", List.of(new SimpleGrantedAuthority("ROLE_tenant-admin")));
    }

    private static Authentication nonAdmin() {
        return new UsernamePasswordAuthenticationToken(
            "bob", "x", List.of(new SimpleGrantedAuthority("ROLE_tenant-member")));
    }

    @Test
    void applyRoutesThroughTheApprovalGateWithTheSameActionTypeAndAdminOnlyPayload() {
        CapturingGate gate = new CapturingGate();
        CodeIntelController controller = new CodeIntelController(new CapturingClient(), gate);

        String response = controller.apply("billing", "ws-1",
            "{\"files\":[{\"path\":\"src/A.java\",\"content\":\"x\"}]}");

        assertEquals(ApplyCodeWorkspaceApprovalExecutor.ACTION_TYPE, gate.actionType.get());
        assertTrue(gate.payload.get().contains("\"adminOnly\":true"), gate.payload.get());
        assertTrue(response.contains("pending_approval"), response);
        assertTrue(response.contains(gate.pendingId().toString()), response);
    }

    @Test
    void runRoutesThroughTheApprovalGateWithTheRunActionType() {
        CapturingGate gate = new CapturingGate();
        CodeIntelController controller = new CodeIntelController(new CapturingClient(), gate);

        String response = controller.run("billing", "ws-1", "{\"command\":\"mvn -q test\"}");

        assertEquals(RunCodeWorkspaceApprovalExecutor.ACTION_TYPE, gate.actionType.get());
        assertTrue(gate.payload.get().contains("\"adminOnly\":true"), gate.payload.get());
        assertTrue(response.contains("pending_approval"), response);
    }

    @Test
    void applyRejectsAnEmptyFilesListBeforeEverFilingARequest() {
        CapturingGate gate = new CapturingGate();
        CodeIntelController controller = new CodeIntelController(new CapturingClient(), gate);

        String response = controller.apply("billing", "ws-1", "{\"files\":[]}");

        assertTrue(response.contains("error"), response);
        assertEquals(null, gate.actionType.get());   // never reached the gate
    }

    @Test
    void resolveApprovalIsForbiddenForNonAdminAndAllowedForAdmin() {
        CapturingClient client = new CapturingClient();
        CodeIntelController controller = new CodeIntelController(client, new CapturingGate());

        assertThrows(CodeIntelForbiddenException.class,
            () -> controller.resolveApproval("ap-1", true, nonAdmin()));

        var response = controller.resolveApproval("ap-1", true, admin());
        assertEquals(200, response.getStatusCode().value());
        assertEquals("resolve:ap-1:true", client.last.get());
    }

    @Test
    void agentResolveIsForbiddenForNonAdminAndAllowedForAdmin() {
        CapturingClient client = new CapturingClient();
        CodeIntelController controller = new CodeIntelController(client, new CapturingGate());

        assertThrows(CodeIntelForbiddenException.class,
            () -> controller.agentResolve("billing", "{}", nonAdmin()));

        var response = controller.agentResolve("billing", "{\"goal\":\"x\",\"test_command\":\"y\"}", admin());
        assertEquals(200, response.getStatusCode().value());
        assertEquals("agentResolve:billing", client.last.get());
    }

    @Test
    void readPassthroughDelegatesToTheClient() {
        CapturingClient client = new CapturingClient();
        CodeIntelController controller = new CodeIntelController(client, new CapturingGate());

        var response = controller.listRepos();
        assertEquals(200, response.getStatusCode().value());
        assertEquals("[]", response.getBody());
        assertEquals("listRepos", client.last.get());
    }

    @Test
    void readPassthroughPropagatesANonSuccessStatusFromTheService() {
        // The 2026-08-14 fix: previously every pass-through endpoint always
        // answered 200 regardless of what code-intel actually returned, which
        // silently broke the web console's error handling (it only triggers on
        // a non-2xx response) - see CodeIntelClient.Result's javadoc.
        CodeIntelClient client = new CodeIntelClient(RestClient.builder(), props()) {
            @Override
            Result createWorkspaceResult(String repoId) {
                return new Result(
                    "{\"error\":\"code execution is disabled on this service (set CODE_INTEL_ALLOW_EXEC=true)\"}",
                    403);
            }
        };
        CodeIntelController controller = new CodeIntelController(client, new CapturingGate());

        var response = controller.createWorkspace("billing");

        assertEquals(403, response.getStatusCode().value());
        assertTrue(response.getBody().contains("CODE_INTEL_ALLOW_EXEC"), response.getBody());
    }

    @Test
    void readPassthroughMapsAnUnreachableServiceToABadGatewayRatherThanASilentTwoHundred() {
        CodeIntelClient client = new CodeIntelClient(RestClient.builder(), props()) {
            @Override
            Result healthResult() {
                return new Result("{\"error\":\"code-intel request failed: Connection refused\"}", 0);
            }
        };
        CodeIntelController controller = new CodeIntelController(client, new CapturingGate());

        var response = controller.health();

        assertEquals(502, response.getStatusCode().value());
    }
}
