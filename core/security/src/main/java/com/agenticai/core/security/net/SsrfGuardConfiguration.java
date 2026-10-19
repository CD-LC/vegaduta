package com.agenticai.core.security.net;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Installs the process-wide {@link SsrfGuard} with the operator's allow-list.
 *
 * <p>{@code app.security.outbound.allowed-private-hosts}: comma-separated
 * {@code host} or {@code host:port} entries that outbound tenant-configured
 * requests may reach even though they resolve to a private address (for
 * example a self-hosted Ollama at {@code ollama:11434}). Empty by default.
 * Only an operator can set it; every listed entry is logged at startup.
 *
 * <p>{@code app.security.outbound.egress-allow-list}: comma-separated {@code host} or
 * {@code *.suffix} entries. When set, tenant-configured outbound URLs may reach ONLY those
 * hosts (the private-address checks still apply). Empty (default) means any public host.
 */
@Configuration(proxyBeanMethods = false)
public class SsrfGuardConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SsrfGuardConfiguration.class);

    @Bean
    public SsrfGuard ssrfGuard(
        @Value("${app.security.outbound.allowed-private-hosts:}") String allowed,
        @Value("${app.security.outbound.egress-allow-list:}") String egress
    ) {
        Set<String> hosts = split(allowed);
        Set<String> egressHosts = split(egress);
        if (!egressHosts.isEmpty()) {
            log.info("Outbound egress allow-list active: tenant-configured URLs may only reach {}", egressHosts);
        }
        if (!hosts.isEmpty()) {
            log.warn("Outbound SSRF guard: operator allow-list lets tenant-configured URLs reach {}", hosts);
        }
        SsrfGuard guard = SsrfGuard.of(hosts, egressHosts, InetAddress::getAllByName);
        SsrfGuard.installDefault(guard);
        return guard;
    }

    private static Set<String> split(String csv) {
        return Arrays.stream(csv.split(","))
            .map(String::strip)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }
}
