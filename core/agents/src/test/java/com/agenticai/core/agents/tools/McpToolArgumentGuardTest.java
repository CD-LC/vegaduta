package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Baseline argument-value security check (Defensive MCP hardening, 2026-08-05) - path traversal and
 * SSRF-shaped-URL detection, plus the deliberately generous fail-open behavior documented on the
 * class itself.
 */
class McpToolArgumentGuardTest {

    // --- fail-open cases: nothing this class should ever block ---------------------

    @Test
    void nullOrBlankArgumentsAreNotBlocked() {
        assertFalse(McpToolArgumentGuard.checkArguments(null).isPresent());
        assertFalse(McpToolArgumentGuard.checkArguments("").isPresent());
        assertFalse(McpToolArgumentGuard.checkArguments("   ").isPresent());
    }

    @Test
    void nonJsonArgumentsAreNotBlocked() {
        // ArgumentSanitizingToolCallingManager already repairs malformed JSON
        // before replay - this class must not duplicate that concern.
        assertFalse(McpToolArgumentGuard.checkArguments("not json at all").isPresent());
    }

    @Test
    void ordinaryArgumentsPassThroughUnaffected() {
        assertFalse(
                McpToolArgumentGuard.checkArguments(
                                "{\"query\": \"latest invoices for Acme Corp\", \"limit\": 10}")
                        .isPresent());
    }

    @Test
    void legitimateRelativePathWithinAWorkspacePassesThrough() {
        assertFalse(
                McpToolArgumentGuard.checkArguments(
                                "{\"path\": \"reports/2026/august/summary.csv\"}")
                        .isPresent());
    }

    @Test
    void normalPublicUrlPassesThrough() {
        assertFalse(
                McpToolArgumentGuard.checkArguments(
                                "{\"url\": \"https://api.example.com/v1/widgets?id=42\"}")
                        .isPresent());
    }

    @Test
    void nonHttpSchemeIsNotTreatedAsAnSsrfTarget() {
        // mailto:/tel:/etc. are out of scope for the SSRF check - only http(s).
        assertFalse(
                McpToolArgumentGuard.checkArguments("{\"contact\": \"mailto:person@example.com\"}")
                        .isPresent());
    }

    @Test
    void unrelatedUseOfDoubleDotDoesNotFalsePositive() {
        // "1.5..2.0" / "v1..v2" style prose must not match the segment-anchored
        // traversal pattern (no leading/trailing '/' or '\\' around the '..').
        assertFalse(
                McpToolArgumentGuard.checkArguments(
                                "{\"range\": \"between v1.2..v1.5, see changelog\"}")
                        .isPresent());
    }

    // --- path traversal --------------------------------------------------------------

    @Test
    void unixStyleParentTraversalIsBlocked() {
        Optional<String> reason =
                McpToolArgumentGuard.checkArguments("{\"path\": \"../../../etc/passwd\"}");
        assertTrue(reason.isPresent());
        assertTrue(reason.get().contains("path-traversal"));
    }

    @Test
    void windowsStyleParentTraversalIsBlocked() {
        assertTrue(
                McpToolArgumentGuard.checkArguments(
                                "{\"path\": \"..\\\\..\\\\Windows\\\\System32\\\\config\\\\SAM\"}")
                        .isPresent());
    }

    @Test
    void traversalNestedInsideAnArrayIsStillCaught() {
        assertTrue(
                McpToolArgumentGuard.checkArguments(
                                "{\"files\": [\"ok.txt\", \"../../secret.env\"]}")
                        .isPresent());
    }

    @Test
    void traversalNestedInsideANestedObjectIsStillCaught() {
        assertTrue(
                McpToolArgumentGuard.checkArguments(
                                "{\"options\": {\"target\": {\"path\": \"a/../../b\"}}}")
                        .isPresent());
    }

    @Test
    void urlEncodedTraversalIsCaught() {
        assertTrue(
                McpToolArgumentGuard.checkArguments("{\"path\": \"%2e%2e/%2e%2e/etc/passwd\"}")
                        .isPresent());
    }

    @Test
    void absolutePathIntoSensitiveUnixLocationIsBlocked() {
        assertTrue(McpToolArgumentGuard.checkArguments("{\"path\": \"/etc/shadow\"}").isPresent());
    }

    @Test
    void absolutePathIntoSensitiveWindowsLocationIsBlocked() {
        assertTrue(
                McpToolArgumentGuard.checkArguments(
                                "{\"path\": \"C:\\\\Windows\\\\System32\\\\drivers\\\\etc\\\\hosts\"}")
                        .isPresent());
    }

    @Test
    void argumentNameIsIrrelevantToTheCheck() {
        // Scanning ALL string values (not just path/file/dir-named params) is a
        // deliberate choice - see the class javadoc - so a traversal payload
        // under an innocuous key name is still caught.
        assertTrue(
                McpToolArgumentGuard.checkArguments("{\"query\": \"../../../etc/passwd\"}")
                        .isPresent());
    }

    // --- SSRF-shaped URLs --------------------------------------------------------------

    @Test
    void loopbackIpUrlIsBlocked() {
        Optional<String> reason =
                McpToolArgumentGuard.checkArguments("{\"url\": \"http://127.0.0.1:8080/admin\"}");
        assertTrue(reason.isPresent());
        assertTrue(reason.get().contains("private, loopback"));
    }

    @Test
    void cloudMetadataIpUrlIsBlocked() {
        assertTrue(
                McpToolArgumentGuard.checkArguments(
                                "{\"url\": \"http://169.254.169.254/latest/meta-data/iam/security-credentials/\"}")
                        .isPresent());
    }

    @Test
    void privateRfc1918RangeUrlIsBlocked() {
        assertTrue(
                McpToolArgumentGuard.checkArguments("{\"url\": \"http://10.0.0.5/internal\"}")
                        .isPresent());
        assertTrue(
                McpToolArgumentGuard.checkArguments("{\"url\": \"http://192.168.1.1/\"}")
                        .isPresent());
        assertTrue(
                McpToolArgumentGuard.checkArguments("{\"url\": \"https://172.16.5.5/\"}")
                        .isPresent());
    }

    @Test
    void localhostHostnameUrlIsBlocked() {
        assertTrue(
                McpToolArgumentGuard.checkArguments("{\"url\": \"http://localhost:5432/\"}")
                        .isPresent());
    }

    @Test
    void gceMetadataHostnameUrlIsBlocked() {
        assertTrue(
                McpToolArgumentGuard.checkArguments(
                                "{\"url\": \"http://metadata.google.internal/computeMetadata/v1/\"}")
                        .isPresent());
    }

    @Test
    void loopbackIpv6UrlIsBlocked() {
        assertTrue(McpToolArgumentGuard.checkArguments("{\"url\": \"http://[::1]/\"}").isPresent());
    }

    @Test
    void publicIpLiteralUrlPassesThrough() {
        // A public IP literal (documentation range, not private/loopback) is a
        // legitimate target and must not be blocked.
        assertFalse(
                McpToolArgumentGuard.checkArguments("{\"url\": \"http://93.184.216.34/\"}")
                        .isPresent());
    }
}
