package com.agenticai.core.common.studioapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StudioAppDomainTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void normalizesAndAcceptsPlainHostnames() {
        assertThat(StudioAppDomainRules.normalizeHostname("  Shop.Example-Store.COM. "))
            .isEqualTo("shop.example-store.com");
        assertThat(StudioAppDomainRules.normalizeHostname("xn--bcher-kva.de")).isEqualTo("xn--bcher-kva.de");
    }

    @Test
    void rejectsInvalidHostnames() {
        for (String bad : new String[] {
            null, "", "localhost", "single", "*.foo.com", "foo.*.com", "1.2.3.4", "10.0.0.1",
            "x.example", "foo.invalid", "bar.onion", "foo.local", "foo.internal",
            "-bad.com", "bad-.com", "a..b.com", "foo.com:8080", "foo.com/path", "bücher.de", "a b.com"
        }) {
            assertThatThrownBy(() -> StudioAppDomainRules.normalizeHostname(bad))
                .as("hostname %s", bad)
                .isInstanceOf(StudioAppDomainRules.InvalidHostnameException.class);
        }
        // suffix match must be on a label boundary
        assertThat(StudioAppDomainRules.normalizeHostname("notexample.com")).isEqualTo("notexample.com");
    }

    @Test
    void decisionLogic() {
        String t = "vd-verify-abc";
        assertThat(StudioAppDomainRules.decide(t, List.of("\"" + t + "\""))).isEqualTo(StudioAppDomainRules.Decision.VERIFIED);
        assertThat(StudioAppDomainRules.decide(t, List.of("v=spf1", t))).isEqualTo(StudioAppDomainRules.Decision.VERIFIED);
        assertThat(StudioAppDomainRules.decide(t, List.of("\"other\""))).isEqualTo(StudioAppDomainRules.Decision.MISMATCH);
        assertThat(StudioAppDomainRules.decide(t, List.of("\"" + t + "x\""))).isEqualTo(StudioAppDomainRules.Decision.MISMATCH);
        assertThat(StudioAppDomainRules.decide(t, List.of())).isEqualTo(StudioAppDomainRules.Decision.NOT_FOUND);
        assertThat(StudioAppDomainRules.decide(t, null)).isEqualTo(StudioAppDomainRules.Decision.LOOKUP_ERROR);
        assertThat(StudioAppDomainRules.txtRecordName("a.b.com")).isEqualTo("_vegaduta-verify.a.b.com");
        assertThat(StudioAppDomainRules.newVerificationToken()).startsWith("vd-verify-").hasSizeLessThanOrEqualTo(64);
    }

    private static StudioAppDomainService service(
        DnsTxtResolver dns, TenantResolutionService tr, StudioAppDomainRepository repo, StudioAppRepository apps
    ) {
        return new StudioAppDomainService(apps, repo, tr, dns, "cname.example.net", "");
    }

    private static StudioAppDomain domain(String host) {
        StudioAppDomain d = new StudioAppDomain();
        d.setHostname(host);
        d.setVerificationToken("vd-verify-tok");
        return d;
    }

    @Test
    void verifyClaimsHostnameWhenTxtMatches() {
        TenantContext.set(TenantContext.of("tenant_a", "u", Set.of()));
        var tr = mock(TenantResolutionService.class);
        when(tr.resolveTenantSchema(any(), any())).thenReturn(Optional.empty());
        var svc = service(n -> List.of("\"vd-verify-tok\""), tr, mock(StudioAppDomainRepository.class), mock(StudioAppRepository.class));
        StudioAppDomain d = domain("shop.example.com");
        svc.applyDecision(d, StudioAppDomainRules.decide(d.getVerificationToken(), List.of("\"vd-verify-tok\"")));
        assertThat(d.getStatus()).isEqualTo(StudioAppDomain.Status.VERIFIED);
        assertThat(d.getVerifiedAt()).isNotNull();
        verify(tr).link(ExternalAccountProvider.STUDIO_APP_DOMAIN, "shop.example.com", "tenant_a");
    }

    @Test
    void verifyRefusedWhenAnotherTenantHoldsHostname() {
        TenantContext.set(TenantContext.of("tenant_b", "u", Set.of()));
        var tr = mock(TenantResolutionService.class);
        when(tr.resolveTenantSchema(ExternalAccountProvider.STUDIO_APP_DOMAIN, "shop.example.com"))
            .thenReturn(Optional.of("tenant_a"));
        var svc = service(n -> List.of(), tr, mock(StudioAppDomainRepository.class), mock(StudioAppRepository.class));
        StudioAppDomain d = domain("shop.example.com");
        svc.applyDecision(d, StudioAppDomainRules.Decision.VERIFIED);
        assertThat(d.getStatus()).isEqualTo(StudioAppDomain.Status.FAILED);
        assertThat(d.getLastError()).isEqualTo("hostname_claimed");
        verify(tr, never()).link(any(), any(), any());
    }

    @Test
    void mismatchFailsButLookupErrorKeepsStatus() {
        TenantContext.set(TenantContext.of("tenant_a", "u", Set.of()));
        var svc = service(n -> List.of(), mock(TenantResolutionService.class),
            mock(StudioAppDomainRepository.class), mock(StudioAppRepository.class));
        StudioAppDomain d = domain("a.example.com");
        svc.applyDecision(d, StudioAppDomainRules.Decision.LOOKUP_ERROR);
        assertThat(d.getStatus()).isEqualTo(StudioAppDomain.Status.PENDING);
        svc.applyDecision(d, StudioAppDomainRules.Decision.MISMATCH);
        assertThat(d.getStatus()).isEqualTo(StudioAppDomain.Status.FAILED);
        svc.applyDecision(d, StudioAppDomainRules.Decision.NOT_FOUND);
        assertThat(d.getStatus()).isEqualTo(StudioAppDomain.Status.PENDING);
    }

    @Test
    void verifyUsesInjectedResolverWithChallengeName() {
        TenantContext.set(TenantContext.of("tenant_a", "u", Set.of()));
        UUID appId = UUID.randomUUID();
        var apps = mock(StudioAppRepository.class);
        when(apps.findById(appId)).thenReturn(Optional.of(new StudioApp()));
        StudioAppDomain d = domain("shop.example.com");
        var repo = mock(StudioAppDomainRepository.class);
        when(repo.findByIdAndAppId(eq(d.getId()), eq(appId))).thenReturn(Optional.of(d));
        var tr = mock(TenantResolutionService.class);
        when(tr.resolveTenantSchema(any(), any())).thenReturn(Optional.empty());
        String[] asked = new String[1];
        var svc = service(n -> { asked[0] = n; return List.of("\"vd-verify-tok\""); }, tr, repo, apps);
        assertThat(svc.verify(appId, d.getId()).getStatus()).isEqualTo(StudioAppDomain.Status.VERIFIED);
        assertThat(asked[0]).isEqualTo("_vegaduta-verify.shop.example.com");
    }

    @Test
    void publicResolutionOnlyForVerifiedLiveHosts() {
        UUID appId = UUID.randomUUID();
        StudioApp app = new StudioApp();
        app.setSlug("slug-1234567890abcd");
        var apps = mock(StudioAppRepository.class);
        when(apps.findById(any())).thenReturn(Optional.of(app));
        StudioAppDomain d = domain("shop.example.com");
        d.setAppId(appId);
        var repo = mock(StudioAppDomainRepository.class);
        when(repo.findByHostname("shop.example.com")).thenReturn(Optional.of(d));
        var tr = mock(TenantResolutionService.class);
        when(tr.resolveTenantSchema(ExternalAccountProvider.STUDIO_APP_DOMAIN, "shop.example.com"))
            .thenReturn(Optional.of("tenant_a"));
        var svc = service(n -> List.of(), tr, repo, apps);

        assertThat(svc.resolveSlugForHost("shop.example.com")).isEmpty(); // still PENDING
        d.setStatus(StudioAppDomain.Status.VERIFIED);
        assertThat(svc.resolveSlugForHost("SHOP.example.com")).contains("slug-1234567890abcd");
        app.setStatus(StudioApp.Status.DISABLED);
        assertThat(svc.resolveSlugForHost("shop.example.com")).isEmpty();
        assertThat(svc.resolveSlugForHost("unknown.example.com")).isEmpty();
        assertThat(svc.resolveSlugForHost("1.2.3.4")).isEmpty();
        assertThat(TenantContext.current()).isEmpty();
    }
}
