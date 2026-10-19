package com.agenticai.core.dbauth;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Just enough of a {@code jdbc:postgresql://host:port/db?params} URL for the token modes. */
final class JdbcUrls {

    private JdbcUrls() {}

    record Endpoint(String host, int port) {}

    static Endpoint endpoint(String jdbcUrl) {
        URI uri = toUri(jdbcUrl);
        if (uri.getHost() == null) {
            throw new IllegalStateException(
                    "spring.datasource.url has no host: cannot build a database login token");
        }
        return new Endpoint(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 5432);
    }

    /**
     * Token modes send a bearer credential as the password: the connection must be encrypted.
     *
     * <p>Mirrors pgjdbc's own reading of the URL (checked against the real driver in {@code
     * DbAuthSettingsTest}): parameter NAMES are matched exactly and case-sensitively and are not
     * decoded ({@code SSLMODE=...} is an unknown parameter the driver ignores), values are
     * URL-decoded and {@code sslmode} values compare case-insensitively, the last occurrence of a
     * name wins, and {@code ssl} only counts when {@code sslmode} is absent ({@code ssl=true} or a
     * bare {@code ssl} then means verify-full).
     */
    static void requireTls(String jdbcUrl, DbAuthMode mode) {
        String query = toUri(jdbcUrl).getRawQuery();
        String sslmode = null;
        boolean ssl = false;
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                String k = eq < 0 ? pair : pair.substring(0, eq);
                String v = eq < 0 ? "" : decode(pair.substring(eq + 1));
                if (k.equals("sslmode")) {
                    sslmode = v.toLowerCase(Locale.ROOT);
                } else if (k.equals("ssl")) {
                    ssl = v.isEmpty() || v.equalsIgnoreCase("true");
                }
            }
        }
        boolean ok =
                sslmode != null
                        ? sslmode.equals("require")
                                || sslmode.equals("verify-ca")
                                || sslmode.equals("verify-full")
                        : ssl;
        if (!ok) {
            throw new IllegalStateException(
                    "app.datasource.auth="
                            + mode.value()
                            + " sends a short-lived token as the database password and requires"
                            + " TLS: add sslmode=require (or verify-full) to spring.datasource.url"
                            + " (Helm: global.postgresql.sslMode). Found sslmode="
                            + (sslmode == null ? "(unset)" : sslmode));
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static URI toUri(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql:")) {
            throw new IllegalStateException(
                    "app.datasource.auth token modes need a jdbc:postgresql://"
                            + " spring.datasource.url");
        }
        try {
            return URI.create(jdbcUrl.substring("jdbc:".length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "spring.datasource.url is not a valid jdbc:postgresql:// URL", e);
        }
    }
}
