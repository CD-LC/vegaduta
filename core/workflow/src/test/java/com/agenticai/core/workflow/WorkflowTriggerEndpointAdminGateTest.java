package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Security audit 2026-07-29, findings F1 and F2 - regression cover for the two {@code
 * ROLE_tenant-admin} matchers that are the REAL gate on the workflow trigger endpoints.
 *
 * <p>Why this test reads source instead of issuing requests: the matchers live in {@code
 * SecurityConfig}'s {@code authorizeHttpRequests} chain, which Spring Security evaluates in its
 * filter chain before any handler runs. Exercising it needs a MockMvc + {@code
 * spring-security-test} harness, and this codebase deliberately has none anywhere (see {@code
 * AccountControllerTest}'s and {@code CompGrantControllerTest}'s own notes - every controller test
 * calls the method directly). Introducing that harness is a bigger change than this security fix
 * should carry. What actually regressed here was not runtime behaviour but a missing LINE - {@code
 * trigger-channel} was simply never added next to {@code webhook}/{@code schedule}, and the {@code
 * webhook} GET was never added next to its own POST/DELETE - so a declaration-level assertion
 * catches the exact failure mode, including the likeliest future repeat (a new trigger sub-resource
 * added without its matcher).
 *
 * <p>Both endpoints are gated at the endpoint level rather than by {@code
 * WorkflowService#requireAccess}'s EDITOR/VIEWER checks, because those checks cannot help here:
 *
 * <ul>
 *   <li>F1, {@code PUT /trigger-channel} (now {@code PUT /nodes/{nodeId}/trigger-channel} -
 *       per-node workflow triggers): an attacker creates their OWN workflow, so they are its owner
 *       and satisfy EDITOR by construction. {@code setTriggerChannel} then calls {@code
 *       WorkflowTriggerNodeService#setTrigger}, a TENANT-GLOBAL claim that re-points every inbound
 *       WhatsApp/SMS/voice message for the whole business into their graph. Same trust tier as
 *       {@code PUT /api/channels/whatsapp/default-agent}, which has always been admin-gated. The
 *       endpoint's WEBHOOK branch also writes {@code webhookAuthMode}, i.e. a downgrade from OAUTH2
 *       back to SHARED_SECRET re-arming an unauthenticated token URL - same matcher covers it.
 *   <li>F2, {@code GET /webhook}: returns the plaintext, permanent, internet-reachable trigger
 *       token, and the VIEWER check in front of it collapses to "any tenant member" for any
 *       workflow whose {@code created_by} is null (every pre-V151 row - see {@code
 *       WorkflowService#requireAccess}'s javadoc).
 * </ul>
 */
class WorkflowTriggerEndpointAdminGateTest {

    /** {@code SecurityConfig}'s source with {@code //} comment lines and all whitespace removed. */
    private static String securityConfigSource;

    @BeforeAll
    static void loadSecurityConfigSource() throws IOException {
        Path source = locateSecurityConfig();
        // Never fail on a layout this test can't find - a false red here would
        // be noise, and the assertions below are the point, not the lookup.
        Assumptions.assumeTrue(
                source != null,
                "SecurityConfig.java not found relative to " + Paths.get("").toAbsolutePath());
        securityConfigSource =
                securityRuleSourceLines(source).stream()
                        .map(String::trim)
                        .filter(
                                line ->
                                        !line.startsWith("//")
                                                && !line.startsWith("*")
                                                && !line.startsWith("/*"))
                        .collect(Collectors.joining(" "))
                        // All whitespace removed, not collapsed: a formatter may wrap a
                        // matcher chain over lines and spaces at any point.
                        .replaceAll("\\s+", "");
    }

    /**
     * SecurityConfig.java plus the {@code *Rules.java} classes its main chain calls: since the
     * chain was split, most rules live in those classes, so a source check must read all of them.
     */
    private static List<String> securityRuleSourceLines(Path securityConfig) throws IOException {
        List<String> lines = new ArrayList<>();
        try (Stream<Path> files = Files.list(securityConfig.getParent())) {
            for (Path file :
                    files.filter(
                                    p ->
                                            p.equals(securityConfig)
                                                    || p.getFileName()
                                                            .toString()
                                                            .endsWith("Rules.java"))
                            .sorted()
                            .toList()) {
                lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
            }
        }
        return lines;
    }

    private static Path locateSecurityConfig() {
        Path relative =
                Paths.get(
                        "core",
                        "security",
                        "src",
                        "main",
                        "java",
                        "com",
                        "agenticai",
                        "core",
                        "security",
                        "SecurityConfig.java");
        // Tests run with the module directory as CWD under Maven, and the repo
        // root under some IDE runners - walk up until one of them resolves.
        for (Path dir = Paths.get("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Granular-RBAC migration (2026-09-23): the workflow trigger matchers moved from {@code
     * ROLE_tenant-admin} to {@code PERM_MANAGE_WORKFLOWS_ADMIN} - under {@code
     * GranularPermissionConverter} a tenant-admin's role seeds the full permission bundle, so the
     * admin gate this test exists to protect is unchanged for admins while scoped users can now be
     * granted it explicitly. The test still pins "gated to an admin-tier authority", just under the
     * new authority name.
     */
    private static void assertAdminGated(String httpMethod, String path) {
        String expected =
                ".requestMatchers(HttpMethod."
                        + httpMethod
                        + ",\""
                        + path
                        + "\").hasAuthority(\"PERM_MANAGE_WORKFLOWS_ADMIN\")";
        assertTrue(
                securityConfigSource.contains(expected),
                () ->
                        httpMethod
                                + " "
                                + path
                                + " must be gated to an admin authority in SecurityConfig. "
                                + "Expected to find: "
                                + expected);
    }

    // -- F1: trigger-channel ------------------------------------------------
    //
    // Per-node workflow triggers: the endpoint moved from
    // /api/workflows/{id}/trigger-channel to
    // /api/workflows/{id}/nodes/{nodeId}/trigger-channel (see
    // WorkflowController) - the matcher pattern below is UPDATED to match,
    // but core/security's SecurityConfig.java itself is OUT OF SCOPE for
    // this change (core/workflow-only task) and has NOT actually been
    // updated yet. This assertion will correctly FAIL until SecurityConfig
    // is updated to match the new path shape - that failure is the intended
    // signal, not a bug in this test: flagged to core-3 (SecurityConfig's
    // owner per the roster) as a required follow-up so these secret-bearing/
    // tenant-global-repoint endpoints don't silently lose their
    // ROLE_tenant-admin gate.

    @Test
    void putTriggerChannelIsGatedToTenantAdmin() {
        assertAdminGated("PUT", "/api/workflows/*/nodes/*/trigger-channel");
    }

    // -- F2: the webhook GET, alongside its already-gated siblings ----------
    // Same SecurityConfig-not-yet-updated caveat as above - see that comment.

    @Test
    void everyVerbOnTheWebhookTriggerPathIsGatedToTenantAdminIncludingTheSecretBearingGet() {
        assertAdminGated("GET", "/api/workflows/*/nodes/*/webhook");
        assertAdminGated("POST", "/api/workflows/*/nodes/*/webhook");
        assertAdminGated("DELETE", "/api/workflows/*/nodes/*/webhook");
    }

    // -- Unchanged neighbours, so a future edit can't quietly drop them -----

    @Test
    void scheduleTriggerWritesRemainGatedToTenantAdmin() {
        assertAdminGated("PUT", "/api/workflows/*/schedule");
        assertAdminGated("DELETE", "/api/workflows/*/schedule");
    }

    /**
     * The gate must be an authority check, not {@code hasRole} - Spring's {@code hasRole("X")}
     * silently prepends {@code ROLE_}, so {@code hasRole("ROLE_tenant-admin")} would look for
     * {@code ROLE_ROLE_tenant-admin} and deny everyone (or, written as {@code
     * hasRole("tenant-admin")}, quietly diverge from the ~40 {@code hasAuthority} lines around it).
     * Pin the convention.
     */
    @Test
    void workflowTriggerMatchersUseHasAuthorityNotHasRole() {
        String workflowMatchers =
                Arrays.stream(securityConfigSource.split("\\.requestMatchers"))
                        .filter(fragment -> fragment.contains("/api/workflows/"))
                        .collect(Collectors.joining(" "));
        assertTrue(
                !workflowMatchers.toLowerCase(Locale.ROOT).contains("hasrole"),
                "Workflow matchers in SecurityConfig must use hasAuthority(\"ROLE_tenant-admin\"), not hasRole(...)");
    }
}
