package com.agenticai.core.security.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The two additive SsrfGuard changes made for SEC-023/026/027 call sites. */
class SsrfGuardAdditionsTest {

    @Test
    void validateWithoutDnsNeverLooksUpANameButKeepsEveryOtherRule() {
        SsrfGuard guard = SsrfGuard.of(Set.of(), host -> {
            if (host.chars().allMatch(c -> Character.isDigit(c) || c == '.') || host.contains(":")) {
                return InetAddress.getAllByName(host);
            }
            throw new AssertionError("must not resolve " + host);
        });
        assertThat(guard.validateWithoutDns("https://api.groq.com/openai").host()).isEqualTo("api.groq.com");
        for (String bad : new String[] {"http://169.254.169.254/", "http://[::1]/", "http://keycloak:8080/",
            "http://metadata.google.internal/", "http://localhost/", "file:///etc/passwd", "http://u:p@example.com/"}) {
            assertThatThrownBy(() -> guard.validateWithoutDns(bad)).as(bad).isInstanceOf(SsrfBlockedException.class);
        }
    }

    @Test
    void hostPortAllowListEntryAlsoAdmitsTheHostAtConnectTime() throws Exception {
        // validate() enforces the port; the resolver cannot see it and must not refuse the host.
        SsrfGuard guard = SsrfGuard.of(Set.of("ollama:11434"), host -> new InetAddress[] {InetAddress.getByName("172.18.0.4")});
        assertThat(guard.validate("http://ollama:11434/api").operatorAllowed()).isTrue();
        assertThat(guard.dnsResolver().resolve("ollama")[0].getHostAddress()).isEqualTo("172.18.0.4");
        // an unlisted private host is still refused by the resolver
        assertThatThrownBy(() -> guard.dnsResolver().resolve("other"))
            .isInstanceOf(SsrfGuard.BlockedAddressException.class);
    }
}
