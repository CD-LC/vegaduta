package com.agenticai.core.admin.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.admin.keycloak.KeycloakAdminClient;
import com.agenticai.core.crypto.EncryptionService;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The load-bearing assertions here are the Keycloak payload shapes: a wrong
 * config key means the tenant's IdP silently mis-brokers (login loop, no
 * signature check) with no error anywhere we can see. These pin the field
 * names Keycloak's IdP API actually honors.
 */
class TenantSsoServiceTest {

    private TenantSsoConfigRepository repository;
    private KeycloakAdminClient keycloak;
    private EncryptionService encryption;
    private TenantSsoService service;

    @BeforeEach
    void setUp() {
        repository = mock(TenantSsoConfigRepository.class);
        keycloak = mock(KeycloakAdminClient.class);
        encryption = mock(EncryptionService.class);
        when(encryption.encrypt(any())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        when(encryption.decrypt(any())).thenAnswer(inv -> ((String) inv.getArgument(0)).substring(4));
        service = new TenantSsoService(
            repository, keycloak, encryption,
            "http://localhost:8180/realms/agentic-ai",
            "https://auth.staging.example.com/realms/agentic-ai",
            "first broker login", TenantSsoServiceTest::resolve);
    }

    /** Offline DNS: *.internal.example resolves to private/metadata addresses, everything else to a public IP. */
    private static java.net.InetAddress[] resolve(String host) throws java.net.UnknownHostException {
        return switch (host) {
            case "metadata.internal.example" -> new java.net.InetAddress[] {
                java.net.InetAddress.getByAddress(host, new byte[] {(byte) 169, (byte) 254, (byte) 169, (byte) 254})};
            case "rfc1918.internal.example" -> new java.net.InetAddress[] {
                java.net.InetAddress.getByAddress(host, new byte[] {10, 0, 0, 5})};
            case "cgnat.internal.example" -> new java.net.InetAddress[] {
                java.net.InetAddress.getByAddress(host, new byte[] {100, 100, 0, 1})};
            case "mixed.internal.example" -> new java.net.InetAddress[] {
                java.net.InetAddress.getByAddress(host, new byte[] {93, (byte) 184, (byte) 216, 34}),
                java.net.InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1})};
            case "nx.example" -> throw new java.net.UnknownHostException(host);
            case "localhost", "127.0.0.1", "::1" -> java.net.InetAddress.getAllByName(host);
            default -> new java.net.InetAddress[] {
                java.net.InetAddress.getByAddress(host, new byte[] {93, (byte) 184, (byte) 216, 34})};
        };
    }

    private static TenantSsoUpsertRequest oidcDiscovery(String url) {
        return new TenantSsoUpsertRequest(
            TenantSsoConfig.ProviderType.OIDC, "Acme Okta", true, url,
            null, null, null, null, null, "okta-client-id", "sekrit",
            null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createdRepresentation() {
        when(repository.findByTenantSchema("acme")).thenReturn(Optional.empty());
        when(keycloak.getIdentityProvider("tenant-acme")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.upsert("acme", oidcReq(), "admin-1");
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(keycloak).createIdentityProvider(captor.capture());
        return captor.getValue();
    }

    @Test
    void sec019_tenantIdpDoesNotTrustEmailAndIsHiddenFromTheSharedLoginPage() {
        Map<String, Object> rep = createdRepresentation();

        assertEquals(false, rep.get("trustEmail"));
        assertEquals(true, rep.get("hideOnLogin"));
        assertEquals("first broker login", rep.get("firstBrokerLoginFlowAlias"));
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) rep.get("config");
        assertEquals("true", cfg.get("hideOnLoginPage"));
    }

    @Test
    void sec019_anExistingIdpWithTrustEmailAndAVisibleButtonIsRepairedOnUpsert() {
        TenantSsoConfig existing = new TenantSsoConfig();
        existing.setTenantSchema("acme");
        existing.setAlias("tenant-acme");
        existing.setProviderType("OIDC");
        existing.setClientSecretEncrypted("enc:stored");
        Map<String, Object> live = new HashMap<>();
        live.put("trustEmail", true);
        live.put("hideOnLogin", false);
        live.put("firstBrokerLoginFlowAlias", "auto-link-everything");
        live.put("postBrokerLoginFlowAlias", "something");
        live.put("config", new HashMap<>(Map.of("hideOnLoginPage", "false")));
        when(repository.findByTenantSchema("acme")).thenReturn(Optional.of(existing));
        when(keycloak.getIdentityProvider("tenant-acme")).thenReturn(Optional.of(live));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsert("acme", oidcReq(), "admin-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(keycloak).updateIdentityProvider(eq("tenant-acme"), captor.capture());
        Map<String, Object> rep = captor.getValue();
        assertEquals(false, rep.get("trustEmail"));
        assertEquals(true, rep.get("hideOnLogin"));
        assertEquals("first broker login", rep.get("firstBrokerLoginFlowAlias"));
        assertTrue(!rep.containsKey("postBrokerLoginFlowAlias"));
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) rep.get("config");
        assertEquals("true", cfg.get("hideOnLoginPage"));
    }

    @Test
    void sec019_aConfiguredDedicatedFirstBrokerLoginFlowIsUsed() {
        TenantSsoService custom = new TenantSsoService(repository, keycloak, encryption,
            "http://localhost:8180/realms/agentic-ai", "", "tenant-idp-no-link", TenantSsoServiceTest::resolve);
        when(repository.findByTenantSchema("acme")).thenReturn(Optional.empty());
        when(keycloak.getIdentityProvider("tenant-acme")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        custom.upsert("acme", oidcReq(), "admin-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(keycloak).createIdentityProvider(captor.capture());
        assertEquals("tenant-idp-no-link", captor.getValue().get("firstBrokerLoginFlowAlias"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sec019_aHardcodedTenantIdMapperPinsUsersToTheOwningTenant() {
        createdRepresentation();

        ArgumentCaptor<Map<String, Object>> mapper = ArgumentCaptor.forClass(Map.class);
        verify(keycloak).createIdentityProviderMapper(eq("tenant-acme"), mapper.capture());
        assertEquals("hardcoded-attribute-idp-mapper", mapper.getValue().get("identityProviderMapper"));
        Map<String, Object> cfg = (Map<String, Object>) mapper.getValue().get("config");
        assertEquals("tenant_id", cfg.get("attribute"));
        assertEquals("acme", cfg.get("attribute.value"));
        assertEquals("FORCE", cfg.get("syncMode"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sec019_theTenantIdMapperIsUpdatedInPlaceNotDuplicated() {
        when(keycloak.listIdentityProviderMappers("tenant-acme")).thenReturn(java.util.List.of(
            new HashMap<>(Map.of("id", "m-1", "name", TenantSsoService.TENANT_ID_MAPPER_NAME))));

        createdRepresentation();

        verify(keycloak, never()).createIdentityProviderMapper(any(), any());
        ArgumentCaptor<Map<String, Object>> mapper = ArgumentCaptor.forClass(Map.class);
        verify(keycloak).updateIdentityProviderMapper(eq("tenant-acme"), eq("m-1"), mapper.capture());
        assertEquals("acme", ((Map<String, Object>) mapper.getValue().get("config")).get("attribute.value"));
    }

    @Test
    void sec019_discoveryAndBackchannelUrlsMustNotResolveToInternalAddresses() {
        for (String host : new String[] {
            "metadata.internal.example", "rfc1918.internal.example", "cgnat.internal.example",
            "mixed.internal.example", "nx.example", "127.0.0.1", "[::1]", "localhost",
        }) {
            assertThrows(IllegalArgumentException.class,
                () -> service.upsert("acme", oidcDiscovery("https://" + host + "/.well-known/openid-configuration"), "u"),
                host);
        }
        TenantSsoUpsertRequest privateToken = new TenantSsoUpsertRequest(
            TenantSsoConfig.ProviderType.OIDC, "x", true, null,
            "https://idp.example.com/authorize", "https://rfc1918.internal.example/token", null, null, null,
            "id", "s", null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> service.upsert("acme", privateToken, "u"));
        TenantSsoUpsertRequest httpJwks = new TenantSsoUpsertRequest(
            TenantSsoConfig.ProviderType.OIDC, "x", true, null,
            "https://idp.example.com/authorize", "https://idp.example.com/token", null,
            "http://idp.example.com/jwks", null, "id", "s", null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> service.upsert("acme", httpJwks, "u"));
        verify(keycloak, never()).createIdentityProvider(any());
    }

    private static TenantSsoUpsertRequest oidcReq() {
        return new TenantSsoUpsertRequest(
            TenantSsoConfig.ProviderType.OIDC, "Acme Okta", true,
            "https://acme.okta.com/.well-known/openid-configuration",
            null, null, null, null, null, "okta-client-id", "sekrit",
            null, null, null, null);
    }

    @Test
    void alias_isTenantPrefixedAndUnique() {
        assertEquals("tenant-acme_corp", TenantSsoService.aliasFor("acme_corp"));
    }

    @Test
    void brokerRedirectUri_prefersThePublicIssuer() {
        assertEquals(
            "https://auth.staging.example.com/realms/agentic-ai/broker/tenant-acme/endpoint",
            service.brokerRedirectUri("acme"));
    }

    @Test
    void upsert_oidc_createsAnIdpWithDiscoveryAndSecret() {
        when(repository.findByTenantSchema("acme")).thenReturn(Optional.empty());
        when(keycloak.getIdentityProvider("tenant-acme")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsert("acme", oidcReq(), "admin-1");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(keycloak).createIdentityProvider(captor.capture());
        Map<String, Object> rep = captor.getValue();
        assertEquals("tenant-acme", rep.get("alias"));
        assertEquals("oidc", rep.get("providerId"));
        assertEquals(true, rep.get("enabled"));

        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) rep.get("config");
        assertEquals("https://acme.okta.com/.well-known/openid-configuration", cfg.get("discoveryEndpoint"));
        assertEquals("true", cfg.get("useDiscoveryEndpoint"));
        assertEquals("okta-client-id", cfg.get("clientId"));
        assertEquals("sekrit", cfg.get("clientSecret"));
        assertEquals("IMPORT", cfg.get("syncMode"));
    }

    @Test
    void upsert_blankSecretOnUpdate_keepsTheStoredOne() {
        TenantSsoConfig existing = new TenantSsoConfig();
        existing.setTenantSchema("acme");
        existing.setAlias("tenant-acme");
        existing.setProviderType("OIDC");
        existing.setClientSecretEncrypted("enc:stored-secret");
        when(repository.findByTenantSchema("acme")).thenReturn(Optional.of(existing));
        when(keycloak.getIdentityProvider("tenant-acme")).thenReturn(Optional.of(new HashMap<>()));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TenantSsoUpsertRequest noSecret = new TenantSsoUpsertRequest(
            TenantSsoConfig.ProviderType.OIDC, "Acme Okta", true,
            "https://acme.okta.com/.well-known/openid-configuration",
            null, null, null, null, null, "okta-client-id", null, // blank secret
            null, null, null, null);
        service.upsert("acme", noSecret, "admin-1");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(keycloak).updateIdentityProvider(eq("tenant-acme"), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) captor.getValue().get("config");
        assertEquals("stored-secret", cfg.get("clientSecret"));
    }

    @Test
    void upsert_oidcRejectsHttpUrls_andMissingClientId() {
        TenantSsoUpsertRequest http = new TenantSsoUpsertRequest(
            TenantSsoConfig.ProviderType.OIDC, "x", true,
            "http://insecure.example.com/.well-known/openid-configuration",
            null, null, null, null, null, "id", "s",
            null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> service.upsert("acme", http, "u"));

        TenantSsoUpsertRequest noClient = new TenantSsoUpsertRequest(
            TenantSsoConfig.ProviderType.OIDC, "x", true,
            "https://acme.okta.com/.well-known/openid-configuration",
            null, null, null, null, null, null, "s",
            null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> service.upsert("acme", noClient, "u"));
    }

    @Test
    void upsert_saml_pushesEndpointsAndStrippedCert() {
        when(repository.findByTenantSchema("acme")).thenReturn(Optional.empty());
        when(keycloak.getIdentityProvider("tenant-acme")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String pem = "-----BEGIN CERTIFICATE-----\nMIIB CERT DATA\n-----END CERTIFICATE-----";
        TenantSsoUpsertRequest saml = new TenantSsoUpsertRequest(
            TenantSsoConfig.ProviderType.SAML, "Acme Entra", true,
            null, null, null, null, null, null, null, null,
            "urn:acme:entra", "https://login.microsoftonline.com/x/saml2", pem, null);
        service.upsert("acme", saml, "admin-1");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(keycloak).createIdentityProvider(captor.capture());
        Map<String, Object> rep = captor.getValue();
        assertEquals("saml", rep.get("providerId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) rep.get("config");
        assertEquals("https://login.microsoftonline.com/x/saml2", cfg.get("singleSignOnServiceUrl"));
        assertEquals("MIIBCERTDATA", cfg.get("signingCertificate"));
        assertEquals("true", cfg.get("validateSignature"));
    }

    @Test
    void delete_removesTheRealmInstanceThenTheRow() {
        TenantSsoConfig existing = new TenantSsoConfig();
        existing.setTenantSchema("acme");
        existing.setAlias("tenant-acme");
        when(repository.findByTenantSchema("acme")).thenReturn(Optional.of(existing));
        when(keycloak.getIdentityProvider("tenant-acme")).thenReturn(Optional.of(Map.of("alias", "tenant-acme")));

        service.delete("acme");
        verify(keycloak).deleteIdentityProvider("tenant-acme");
        verify(repository).delete(existing);
    }

    @Test
    void delete_skipsKeycloakCall_whenInstanceAlreadyGone() {
        TenantSsoConfig existing = new TenantSsoConfig();
        existing.setTenantSchema("acme");
        existing.setAlias("tenant-acme");
        when(repository.findByTenantSchema("acme")).thenReturn(Optional.of(existing));
        when(keycloak.getIdentityProvider("tenant-acme")).thenReturn(Optional.empty());

        service.delete("acme");
        verify(keycloak, never()).deleteIdentityProvider(any());
        verify(repository).delete(existing);
    }
}
