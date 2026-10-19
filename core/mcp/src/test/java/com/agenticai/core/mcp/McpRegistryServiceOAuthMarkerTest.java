package com.agenticai.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.mcp.oauth.McpOAuthService;
import com.agenticai.core.mcp.oauth.McpOAuthUrls;
import com.agenticai.core.secrets.SecretReferenceResolver;
import com.agenticai.core.secrets.SecretVaultService;
import com.agenticai.core.security.TenantLimits;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** {@link McpRegistryService#stampOAuthMarkerOnRemote}: which registrations the generic OAuth flow may take over. */
class McpRegistryServiceOAuthMarkerTest {

    private static final String RESOURCE = "https://mcp.example.com/mcp";

    private McpServerRegistrationRepository repository;
    private McpRegistryService service;

    @BeforeEach
    void setUp() {
        repository = mock(McpServerRegistrationRepository.class);
        service = new McpRegistryService(repository, mock(EncryptionService.class), new McpRemoteUrlGuard(""),
            new McpStdioCommandGuard(""), mock(TenantLimits.class), new McpCatalogService(),
            new SecretReferenceResolver(mock(SecretVaultService.class)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static McpServerRegistration remote(McpServerType type, String url, String marker) {
        McpServerRegistration r = new McpServerRegistration("r-" + UUID.randomUUID(), type, "d", true);
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        r.configureRemote(url, null);
        r.setOauthProvider(marker);
        return r;
    }

    @Test
    void stampsOnlyUnownedRemoteRegistrationsTheResourceCovers() {
        McpServerRegistration covered = remote(McpServerType.STREAMABLE_HTTP, RESOURCE, null);
        McpServerRegistration coveredSse = remote(McpServerType.SSE, RESOURCE + "/sse", null);
        McpServerRegistration alreadyOurs = remote(McpServerType.STREAMABLE_HTTP, RESOURCE, McpOAuthService.MARKER);
        McpServerRegistration otherHost = remote(McpServerType.STREAMABLE_HTTP, "https://other.example.com/mcp", null);
        McpServerRegistration sibling = remote(McpServerType.STREAMABLE_HTTP, "https://mcp.example.com/mcp-evil", null);
        McpServerRegistration plainHttp = remote(McpServerType.STREAMABLE_HTTP, "http://mcp.example.com/mcp", null);
        McpServerRegistration atlassian = remote(McpServerType.STREAMABLE_HTTP, RESOURCE, "ATLASSIAN");
        McpServerRegistration provider = remote(McpServerType.STREAMABLE_HTTP, RESOURCE, "oauth:github");
        McpServerRegistration stdio = new McpServerRegistration("local", McpServerType.STDIO, "d", true);
        ReflectionTestUtils.setField(stdio, "id", UUID.randomUUID());
        when(repository.findAll()).thenReturn(List.of(
            covered, coveredSse, alreadyOurs, otherHost, sibling, plainHttp, atlassian, provider, stdio));

        List<UUID> stamped = service.stampOAuthMarkerOnRemote(
            url -> McpOAuthUrls.covers(RESOURCE, url), McpOAuthService.MARKER);

        assertEquals(List.of(covered.getId(), coveredSse.getId(), alreadyOurs.getId()), stamped);
        assertEquals(McpOAuthService.MARKER, covered.getOauthProvider());
        assertEquals(McpOAuthService.MARKER, coveredSse.getOauthProvider());
        assertNull(otherHost.getOauthProvider());
        assertNull(sibling.getOauthProvider());
        assertNull(plainHttp.getOauthProvider());
        assertEquals("ATLASSIAN", atlassian.getOauthProvider(), "another flow's registration is never taken over");
        assertEquals("oauth:github", provider.getOauthProvider());
        assertNull(stdio.getOauthProvider());
    }
}
