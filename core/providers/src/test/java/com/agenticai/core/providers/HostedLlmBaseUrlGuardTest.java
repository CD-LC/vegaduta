package com.agenticai.core.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.providers.validation.ProviderValidationException;
import com.agenticai.core.security.net.SsrfGuard;
import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SSRF regression tests for LLM base URLs (hosted pool + tenant BYOK). Since
 * SEC-023 the guard delegates to the shared {@link SsrfGuard}: it resolves the
 * host, covers the full reserved-range list and refuses compose service names.
 * Every test that needs DNS uses a fake resolver so the suite runs offline.
 */
class HostedLlmBaseUrlGuardTest {

    /** Names in the table resolve as listed; any other name resolves to a public address; literals parse locally. */
    private static SsrfGuard guard(Map<String, String> table, Set<String> allowList) {
        return SsrfGuard.of(allowList, host -> {
            String ip = table.get(host);
            if (ip != null) {
                return new InetAddress[] {InetAddress.getByName(ip)};
            }
            if (host.chars().allMatch(c -> Character.isDigit(c) || c == '.') || host.contains(":")) {
                return InetAddress.getAllByName(host);
            }
            return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
        });
    }

    private static SsrfGuard publicDns() {
        return guard(Map.of(), Set.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://api.groq.com/openai",
        "https://api.together.xyz",
        "http://api.example.com:8080/v1",
        "https://generativelanguage.googleapis.com",
        // A real public IP literal must NOT be blocked just for being an IP.
        "http://8.8.8.8/v1"
    })
    void allowsOrdinaryPublicBaseUrls(String baseUrl) {
        HostedLlmBaseUrlGuard.checkAllowed(baseUrl, publicDns());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://127.0.0.1/",
        "http://localhost/",
        "http://localhost./",              // M1-style trailing dot
        "http://10.0.0.5/",
        "http://172.20.0.5/",
        "http://192.168.1.1/",
        "http://169.254.169.254/latest/meta-data/", // cloud metadata endpoint
        "http://168.63.129.16/",            // Azure WireServer
        "http://100.64.0.1/",               // CGNAT
        "http://192.0.0.8/",
        "http://198.18.0.1/",
        "http://240.0.0.1/",
        "http://0.0.0.0/",
        "http://host.docker.internal/",
        "http://gateway.docker.internal/",
        "http://[::1]/",
        "http://[fc00::1]/",                 // IPv6 ULA
        "http://[fe80::1]/",                 // IPv6 link-local
        "http://[::ffff:7f00:1]/",
        "http://[0:0:0:0:0:ffff:7f00:1]/",
        "http://[::ffff:ac14:5]/",
        "http://[64:ff9b::a9fe:a9fe]/",      // NAT64 onto metadata
        // SEC-023: compose service names used to pass (no DNS, not literals).
        "http://keycloak:8080/",
        "http://core:8080/actuator",
        "http://postgres:5432/",
        "http://metadata.google.internal/"
    })
    void blocksInternalTargetsWithTheDefaultGuard(String baseUrl) {
        assertThatThrownBy(() -> HostedLlmBaseUrlGuard.checkAllowed(baseUrl))
            .isInstanceOf(ProviderValidationException.class)
            .hasMessageContaining("not allowed");
    }

    @Test
    void blocksAHostnameWhoseDnsPointsInside() {
        // 127.0.0.1.nip.io / attacker DNS / rebinding - resolved, then classified.
        SsrfGuard dns = guard(Map.of("127.0.0.1.nip.io", "127.0.0.1", "llm.attacker.example", "10.1.2.3"), Set.of());
        assertThatThrownBy(() -> HostedLlmBaseUrlGuard.checkAllowed("http://127.0.0.1.nip.io:11434", dns))
            .isInstanceOf(ProviderValidationException.class);
        assertThatThrownBy(() -> HostedLlmBaseUrlGuard.checkAllowed("https://llm.attacker.example/v1", dns))
            .isInstanceOf(ProviderValidationException.class)
            // no port-scan / network-map oracle: the resolved address is never echoed
            .hasMessageNotContaining("10.1.2.3");
    }

    @Test
    void refusalTextIsTheSameForEveryInternalPort() {
        SsrfGuard dns = publicDns();
        String a = messageOf("http://10.0.0.5:22/", dns);
        String b = messageOf("http://10.0.0.5:5432/", dns);
        String c = messageOf("http://10.0.0.5:1/", dns);
        assertThat(a).isEqualTo(b).isEqualTo(c).doesNotContain("10.0.0.5");
    }

    @Test
    void operatorAllowListIsTheOnlyWayToReachASelfHostedPrivateOllama() {
        SsrfGuard allowed = guard(Map.of("ollama", "172.18.0.4"), Set.of("ollama:11434"));
        HostedLlmBaseUrlGuard.checkAllowed("http://ollama:11434", allowed);
        assertThatThrownBy(() -> HostedLlmBaseUrlGuard.checkAllowed("http://ollama:22", allowed))
            .isInstanceOf(ProviderValidationException.class);
        assertThatThrownBy(() -> HostedLlmBaseUrlGuard.checkAllowed(
                "http://ollama:11434", guard(Map.of("ollama", "172.18.0.4"), Set.of())))
            .isInstanceOf(ProviderValidationException.class);
    }

    @Test
    void perRequestRecheckNeverResolvesNamesButStillRefusesLiteralsAndComposeNames() {
        SsrfGuard failIfResolved = SsrfGuard.of(Set.of(), host -> {
            if (host.chars().allMatch(c -> Character.isDigit(c) || c == '.') || host.contains(":")) {
                return InetAddress.getAllByName(host);
            }
            throw new AssertionError("must not resolve " + host);
        });
        HostedLlmBaseUrlGuard.checkAllowedWithoutDns("https://api.groq.com/openai", failIfResolved);
        assertThatThrownBy(() -> HostedLlmBaseUrlGuard.checkAllowedWithoutDns("http://169.254.169.254/", failIfResolved))
            .isInstanceOf(ProviderValidationException.class);
        assertThatThrownBy(() -> HostedLlmBaseUrlGuard.checkAllowedWithoutDns("http://keycloak:8080/", failIfResolved))
            .isInstanceOf(ProviderValidationException.class);
    }

    @Test
    void blocksUserinfoConfusion() {
        assertThatThrownBy(() -> HostedLlmBaseUrlGuard.checkAllowed("http://api.groq.com@127.0.0.1/", publicDns()))
            .isInstanceOf(ProviderValidationException.class);
        assertThatThrownBy(() -> HostedLlmBaseUrlGuard.checkAllowed("http://127.0.0.1@api.groq.com/", publicDns()))
            .isInstanceOf(ProviderValidationException.class);
    }

    @Test
    void blocksNonHttpSchemes() {
        assertThatThrownBy(() -> HostedLlmBaseUrlGuard.checkAllowed("ftp://api.groq.com/", publicDns()))
            .isInstanceOf(ProviderValidationException.class);
        assertThatThrownBy(() -> HostedLlmBaseUrlGuard.checkAllowed("file:///etc/passwd", publicDns()))
            .isInstanceOf(ProviderValidationException.class);
    }

    @Test
    void nullOrBlankIsANoop() {
        HostedLlmBaseUrlGuard.checkAllowed(null);
        HostedLlmBaseUrlGuard.checkAllowed("");
        HostedLlmBaseUrlGuard.checkAllowed("   ");
    }

    private static String messageOf(String url, SsrfGuard guard) {
        try {
            HostedLlmBaseUrlGuard.checkAllowed(url, guard);
        } catch (ProviderValidationException e) {
            return e.getMessage();
        }
        throw new AssertionError("expected " + url + " to be refused");
    }
}
