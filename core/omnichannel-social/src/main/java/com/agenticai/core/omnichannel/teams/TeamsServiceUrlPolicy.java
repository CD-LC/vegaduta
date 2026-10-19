package com.agenticai.core.omnichannel.teams;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SEC-047: which Bot Framework {@code serviceUrl}s this platform will talk to.
 *
 * <p>The serviceUrl arrives in the inbound activity and is where the bot's Entra (AAD) access token
 * is sent on every reply. An attacker-chosen serviceUrl therefore means the bot's bearer token is
 * handed to the attacker. Two independent defences:
 *
 * <ol>
 *   <li>the JWT's {@code serviceurl} claim must equal the activity's serviceUrl ({@link
 *       TeamsBotFrameworkJwtValidator}), and
 *   <li>the host must be https and on this allowlist before any token is sent ({@link
 *       TeamsApiClient#sendActivity}), which also covers routes stored before the claim binding
 *       existed.
 * </ol>
 *
 * <p>Allowlist entries ({@code app.teams.service-url-allowed-hosts}, comma separated): {@code
 * *.example.com} matches strict subdomains of {@code example.com}; anything else is an exact host.
 * Note that the default deliberately lists {@code smba.trafficmanager.net} exactly and NOT {@code
 * *.trafficmanager.net}: any Azure customer can create {@code <anything>.trafficmanager.net} and
 * point it at their own server.
 */
@Component
public class TeamsServiceUrlPolicy {

    /**
     * Microsoft-operated Bot Framework / Teams connector hosts (public, GCC, GCC-High/DoD, Azure
     * China).
     */
    static final String DEFAULT_ALLOWED_HOSTS =
            "smba.trafficmanager.net,*.botframework.com,*.teams.microsoft.com,*.teams.microsoft.us,"
                    + "*.botframework.azure.us,*.botframework.azure.cn";

    private final List<String> allowed;

    public TeamsServiceUrlPolicy(
            @Value("${app.teams.service-url-allowed-hosts:" + DEFAULT_ALLOWED_HOSTS + "}")
                    String allowedHosts) {
        this.allowed =
                Arrays.stream((allowedHosts == null ? "" : allowedHosts).split(","))
                        .map(s -> s.trim().toLowerCase(Locale.ROOT))
                        .filter(s -> !s.isEmpty())
                        .toList();
    }

    /**
     * @return true when {@code serviceUrl} is an https URL (default port, no userinfo) on an
     *     allowed host.
     */
    public boolean isAllowed(String serviceUrl) {
        URI uri = parse(serviceUrl);
        if (uri == null
                || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getRawUserInfo() != null) {
            return false;
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        for (String entry : allowed) {
            if (entry.startsWith("*.")) {
                String suffix = entry.substring(1); // ".example.com"
                if (host.endsWith(suffix) && host.length() > suffix.length()) {
                    return true;
                }
            } else if (host.equals(entry)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Canonical form for comparing a JWT {@code serviceurl} claim with an activity's serviceUrl:
     * lower-case scheme and host, default port dropped, trailing slashes stripped. Returns null for
     * an unparseable value.
     */
    static String normalise(String serviceUrl) {
        URI uri = parse(serviceUrl);
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            return null;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort =
                port == -1
                        || ("https".equals(scheme) && port == 443)
                        || ("http".equals(scheme) && port == 80);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return scheme
                + "://"
                + uri.getHost().toLowerCase(Locale.ROOT)
                + (defaultPort ? "" : ":" + port)
                + path
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }

    private static URI parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new URI(value.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
