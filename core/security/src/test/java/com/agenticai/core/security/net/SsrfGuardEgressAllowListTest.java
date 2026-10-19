package com.agenticai.core.security.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The operator egress allow-list (OWASP ASI02/LLM06): hosts outside it are refused before any lookup. */
class SsrfGuardEgressAllowListTest {

    private static final SsrfGuard.HostResolver PUBLIC = host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")};

    private static SsrfGuard guard(Set<String> private_, Set<String> egress) {
        return SsrfGuard.of(private_, egress, PUBLIC);
    }

    @Test
    void emptyListKeepsTheDefaultOfAnyPublicHost() {
        SsrfGuard g = guard(Set.of(), Set.of());
        assertThat(g.validate("https://anything.example.org/x").host()).isEqualTo("anything.example.org");
    }

    @Test
    void onlyListedHostsAndSubdomainsPassExactAndWildcard() {
        SsrfGuard g = guard(Set.of(), Set.of("api.example.com", "*.partner.io"));
        assertThat(g.validate("https://api.example.com/v1").host()).isEqualTo("api.example.com");
        assertThat(g.validate("https://a.b.partner.io/").host()).isEqualTo("a.b.partner.io");
        for (String bad : new String[] {"https://example.com/", "https://evil.com/", "https://partner.io/",
            "https://notpartner.io/", "https://api.example.com.evil.com/", "https://x.example.com/"}) {
            assertThatThrownBy(() -> g.validate(bad)).as(bad)
                .isInstanceOf(SsrfBlockedException.class)
                .hasMessageContaining("allow-list");
        }
    }

    @Test
    void theListIsCaseInsensitiveAndAlsoGovernsRedirectsAndTheConnectTimeResolver() throws Exception {
        SsrfGuard g = guard(Set.of(), Set.of("API.Example.com"));
        assertThat(g.validate("https://api.example.com/").host()).isEqualTo("api.example.com");
        assertThatThrownBy(() -> g.resolveRedirect(java.net.URI.create("https://api.example.com/a"), "https://evil.com/b"))
            .isInstanceOf(SsrfBlockedException.class);
        assertThat(g.dnsResolver().resolve("api.example.com")).hasSize(1);
        assertThatThrownBy(() -> g.dnsResolver().resolve("evil.com")).isInstanceOf(SsrfGuard.BlockedAddressException.class);
    }

    @Test
    void theAllowListDoesNotWeakenThePrivateAddressChecks() {
        SsrfGuard g = SsrfGuard.of(Set.of(), Set.of("rebind.example.com"),
            host -> new InetAddress[] {InetAddress.getByName("169.254.169.254")});
        assertThatThrownBy(() -> g.validate("https://rebind.example.com/"))
            .isInstanceOf(SsrfBlockedException.class)
            .hasMessageContaining("private or internal");
    }

    @Test
    void operatorAllowedPrivateHostsAreExemptFromTheEgressList() {
        SsrfGuard g = guard(Set.of("ollama:11434"), Set.of("api.example.com"));
        assertThat(g.validate("http://ollama:11434/api").operatorAllowed()).isTrue();
    }
}
