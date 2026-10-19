package com.agenticai.core.admin.localauth;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Decides which browser origins may register or use a passkey, and derives the WebAuthn RP ID from
 * them. The web console's server-side proxy forwards the browser's own {@code Origin} as
 * {@code X-Vegaduta-Origin} (core only ever sees the proxy's address); a direct client sends
 * {@code Origin}.
 *
 * <p>Accepted: {@code https://<host>[:port]}, or {@code http://localhost[:port]} (browsers treat it
 * as a secure context). Refused: an IP-literal host, because WebAuthn forbids an IP address as an RP
 * ID, and anything with a path, query, fragment or credentials. The RP ID is the host. When {@code
 * app.auth.local.passkey.origins} is set, only the listed origins are accepted.
 */
final class PasskeyOrigins {

    /** A validated origin in the exact form browsers put in clientDataJSON, and its RP ID. */
    record Resolved(String origin, String rpId) {}

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private final Set<String> allowed;

    PasskeyOrigins(String configured) {
        this.allowed =
                configured == null || configured.isBlank()
                        ? Set.of()
                        : Arrays.stream(configured.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .map(PasskeyOrigins::parse)
                                .filter(r -> r != null)
                                .map(Resolved::origin)
                                .collect(Collectors.toUnmodifiableSet());
    }

    /** @throws LocalAuthException {@code passkey_origin_unsupported} when the origin may not be used */
    Resolved resolve(String originHeader) {
        Resolved resolved = originHeader == null ? null : parse(originHeader.trim());
        if (resolved == null) {
            throw LocalAuthException.passkeyOriginUnsupported(
                    "Passkeys need the console to be opened at an https address (or on localhost), not at an IP address.");
        }
        if (!allowed.isEmpty() && !allowed.contains(resolved.origin())) {
            throw LocalAuthException.passkeyOriginUnsupported("Passkeys are not enabled for this address.");
        }
        return resolved;
    }

    /** Null when the text is not an acceptable origin. */
    static Resolved parse(String text) {
        if (text == null || text.isEmpty() || text.length() > 255) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean bareOrigin =
                uri.getUserInfo() == null
                        && uri.getQuery() == null
                        && uri.getFragment() == null
                        && (uri.getPath() == null || uri.getPath().isEmpty());
        if (!bareOrigin || host.isEmpty() || host.startsWith("[") || host.contains(":") || IPV4.matcher(host).matches()) {
            return null;
        }
        boolean https = scheme.equals("https");
        if (!https && !(scheme.equals("http") && host.equals("localhost"))) {
            return null;
        }
        int port = uri.getPort();
        boolean defaultPort = port == -1 || (https && port == 443) || (!https && port == 80);
        if (port < -1 || port == 0 || port > 65535) {
            return null;
        }
        return new Resolved(scheme + "://" + host + (defaultPort ? "" : ":" + port), host);
    }
}
