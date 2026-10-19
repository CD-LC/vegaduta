package com.agenticai.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.mcp.dto.McpServerRegistrationRequest;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantPlan;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** SEC-002: catalog trust binds the exact launch spec, not the name. */
class McpRegistrySec002Test extends McpRegistryServiceFixture {

    // --- SEC-002: catalog trust binds the exact launch spec, not the name -------

    private static final List<String> GITHUB_ARGS = List.of("-y", "@modelcontextprotocol/server-github");

    private McpServerRegistrationResponse registerNew(String name, String command, List<String> args, Map<String, String> env) {
        when(repository.findByName(name)).thenReturn(Optional.empty());
        return service.register(new McpServerRegistrationRequest(name, McpServerType.STDIO, "d", true, command, args, env));
    }

    @Test
    void sec002_exactCatalogSpecIsNotRequired() {
        McpServerRegistrationResponse r = registerNew("github", "npx", GITHUB_ARGS,
            Map.of("GITHUB_PERSONAL_ACCESS_TOKEN", "ghp_x"));
        assertEquals(McpReviewStatus.NOT_REQUIRED, r.reviewStatus());
    }

    @Test
    void sec002_catalogNameIsCaseInsensitiveButTheSpecStillHasToMatch() {
        McpServerRegistrationResponse r = registerNew("GitHub", "npx", GITHUB_ARGS, Map.of());
        assertEquals(McpReviewStatus.NOT_REQUIRED, r.reviewStatus());
    }

    @Test
    void sec002_catalogNameWithADifferentPackageIsPending() {
        McpServerRegistrationResponse r = registerNew("github", "npx", List.of("-y", "attacker-pkg"),
            Map.of("GITHUB_PERSONAL_ACCESS_TOKEN", "ghp_x"));
        assertEquals(McpReviewStatus.PENDING, r.reviewStatus());
    }

    @Test
    void sec002_catalogNameWithADifferentPackageIsRefusedOnTheFreePlan() {
        setTenant("free-tenant", TenantPlan.FREE);
        when(repository.count()).thenReturn(0L);
        when(repository.findByName("github")).thenReturn(Optional.empty());
        McpServerRegistrationRequest request = new McpServerRegistrationRequest(
            "github", McpServerType.STDIO, "d", true, "npx", List.of("-y", "attacker-pkg"), Map.of());

        assertThrows(TenantLimitExceededException.class, () -> service.register(request));
        verify(repository, never()).save(any());
    }

    @Test
    void sec002_exactCatalogSpecIsAllowedOnTheFreePlan() {
        setTenant("free-tenant", TenantPlan.FREE);
        when(repository.count()).thenReturn(0L);
        McpServerRegistrationResponse r = registerNew("github", "npx", GITHUB_ARGS,
            Map.of("GITHUB_PERSONAL_ACCESS_TOKEN", "ghp_x"));
        assertEquals(McpReviewStatus.NOT_REQUIRED, r.reviewStatus());
    }

    @Test
    void sec002_appendedRegistryArgIsPending() {
        List<String> args = List.of("-y", "@modelcontextprotocol/server-github", "--registry", "https://evil.example.com");
        assertEquals(McpReviewStatus.PENDING, registerNew("github", "npx", args, Map.of()).reviewStatus());
    }

    @Test
    void sec002_appendedSecondPackageIsPending() {
        List<String> args = List.of("-y", "@modelcontextprotocol/server-github", "-y", "attacker-pkg");
        assertEquals(McpReviewStatus.PENDING, registerNew("github", "npx", args, Map.of()).reviewStatus());
    }

    @Test
    void sec002_prependedPackageIsPending() {
        List<String> args = List.of("-y", "-p", "attacker-pkg", "@modelcontextprotocol/server-github");
        assertEquals(McpReviewStatus.PENDING, registerNew("github", "npx", args, Map.of()).reviewStatus());
    }

    @Test
    void sec002_differentVersionIsPending() {
        List<String> args = List.of("-y", "@modelcontextprotocol/server-github@9.9.9");
        assertEquals(McpReviewStatus.PENDING, registerNew("github", "npx", args, Map.of()).reviewStatus());
    }

    @Test
    void sec002_pinnedCatalogVersionMustMatchExactly() {
        // playwright-vision's catalog spec pins @playwright/mcp@0.0.79 but has no
        // dynamicCommand at all - no command-carrying request can ever match it.
        List<String> args = List.of("-y", "@playwright/mcp@0.0.79", "--caps=vision", "--browser=chromium");
        assertEquals(McpReviewStatus.PENDING, registerNew("playwright-vision", "npx", args, Map.of()).reviewStatus());
    }

    @Test
    void sec002_commandCaseAndWhitespaceTricksArePending() {
        assertEquals(McpReviewStatus.PENDING, registerNew("github", "NPX", GITHUB_ARGS, Map.of()).reviewStatus());
        assertEquals(McpReviewStatus.PENDING, registerNew("github", "npx ", GITHUB_ARGS, Map.of()).reviewStatus());
        assertEquals(McpReviewStatus.PENDING, registerNew("github", " npx", GITHUB_ARGS, Map.of()).reviewStatus());
        assertEquals(McpReviewStatus.PENDING, registerNew("github", "npx.cmd", GITHUB_ARGS, Map.of()).reviewStatus());
        List<String> spacedArgs = List.of("-y", "@modelcontextprotocol/server-github ");
        assertEquals(McpReviewStatus.PENDING, registerNew("github", "npx", spacedArgs, Map.of()).reviewStatus());
    }

    @Test
    void sec002_undeclaredEnvKeyIsPending() {
        McpServerRegistrationResponse r = registerNew("github", "npx", GITHUB_ARGS,
            Map.of("GITHUB_PERSONAL_ACCESS_TOKEN", "ghp_x", "EXTRA_SETTING", "v"));
        assertEquals(McpReviewStatus.PENDING, r.reviewStatus());
    }

    @Test
    void sec002_differentLauncherIsPending() {
        List<String> args = List.of("@modelcontextprotocol/server-github");
        assertEquals(McpReviewStatus.PENDING, registerNew("github", "uvx", args, Map.of()).reviewStatus());
    }

    @Test
    void sec002_placeholderArgAcceptsATenantValueButNeverAFlag() {
        McpServerRegistrationResponse ok = registerNew("postgres", "npx",
            List.of("-y", "@modelcontextprotocol/server-postgres", "postgresql://u:p@db:5432/x"), Map.of());
        assertEquals(McpReviewStatus.NOT_REQUIRED, ok.reviewStatus());

        McpServerRegistrationResponse flag = registerNew("postgres", "npx",
            List.of("-y", "@modelcontextprotocol/server-postgres", "--registry=https://evil.example.com"), Map.of());
        assertEquals(McpReviewStatus.PENDING, flag.reviewStatus());
    }

    @Test
    void sec002_updateThatSwapsThePackageOfACatalogServerGoesBackToPending() {
        McpServerRegistration existing = new McpServerRegistration("github", McpServerType.STDIO, "d", true);
        existing.configureDynamic("npx", "[\"-y\",\"@modelcontextprotocol/server-github\"]", null);
        existing.markReviewNotRequired();
        when(repository.findByName("github")).thenReturn(Optional.of(existing));

        McpServerRegistrationResponse r = service.register(new McpServerRegistrationRequest(
            "github", McpServerType.STDIO, "d", true, "npx", List.of("-y", "attacker-pkg"), null));

        assertEquals(McpReviewStatus.PENDING, r.reviewStatus());
    }

    @Test
    void sec002_updateThatSwapsThePackageIsRefusedOnTheFreePlan() {
        setTenant("free-tenant", TenantPlan.FREE);
        McpServerRegistration existing = new McpServerRegistration("github", McpServerType.STDIO, "d", true);
        existing.configureDynamic("npx", "[\"-y\",\"@modelcontextprotocol/server-github\"]", null);
        existing.markReviewNotRequired();
        when(repository.findByName("github")).thenReturn(Optional.of(existing));

        McpServerRegistrationRequest request = new McpServerRegistrationRequest(
            "github", McpServerType.STDIO, "d", true, "npx", List.of("-y", "attacker-pkg"), Map.of());

        assertThrows(TenantLimitExceededException.class, () -> service.register(request));
        verify(repository, never()).save(any());
        assertEquals(McpReviewStatus.NOT_REQUIRED, existing.getReviewStatus());
        assertEquals("[\"-y\",\"@modelcontextprotocol/server-github\"]", existing.getArgs());
    }

    @Test
    void sec002_updateThatAddsAnUndeclaredEnvKeyIsPending() {
        when(encryptionService.decrypt("stored")).thenReturn("{\"GITHUB_PERSONAL_ACCESS_TOKEN\":\"ghp_x\"}");
        McpServerRegistration existing = new McpServerRegistration("github", McpServerType.STDIO, "d", true);
        existing.configureDynamic("npx", "[\"-y\",\"@modelcontextprotocol/server-github\"]", "stored");
        existing.markReviewNotRequired();
        when(repository.findByName("github")).thenReturn(Optional.of(existing));

        McpServerRegistrationResponse r = service.register(new McpServerRegistrationRequest(
            "github", McpServerType.STDIO, "d", true, "npx", GITHUB_ARGS, Map.of("SOME_OTHER_VAR", "v")));

        assertEquals(McpReviewStatus.PENDING, r.reviewStatus());
    }

    @Test
    void sec002_exactSpecUpdateOnTheFreePlanStaysNotRequired() {
        setTenant("free-tenant", TenantPlan.FREE);
        when(encryptionService.decrypt("stored")).thenReturn("{\"GITHUB_PERSONAL_ACCESS_TOKEN\":\"old\"}");
        McpServerRegistration existing = new McpServerRegistration("github", McpServerType.STDIO, "d", true);
        existing.configureDynamic("npx", "[\"-y\",\"@modelcontextprotocol/server-github\"]", "stored");
        existing.markReviewNotRequired();
        when(repository.findByName("github")).thenReturn(Optional.of(existing));

        McpServerRegistrationResponse r = service.register(new McpServerRegistrationRequest(
            "github", McpServerType.STDIO, "d", true, "npx", GITHUB_ARGS, Map.of("GITHUB_PERSONAL_ACCESS_TOKEN", "new")));

        assertEquals(McpReviewStatus.NOT_REQUIRED, r.reviewStatus());
    }

    @Test
    void sec002_remoteServerReusingACatalogNameWithAnotherUrlIsRefusedOnTheFreePlan() {
        setTenant("free-tenant", TenantPlan.FREE);
        when(repository.count()).thenReturn(0L);
        when(repository.findByName("atlassian")).thenReturn(Optional.empty());
        McpServerRegistrationRequest request = new McpServerRegistrationRequest(
            "atlassian", McpServerType.STREAMABLE_HTTP, "d", true, null, null, Map.of(), "https://mcp.example.com/mcp");

        assertThrows(TenantLimitExceededException.class, () -> service.register(request));
        verify(repository, never()).save(any());
    }
}
