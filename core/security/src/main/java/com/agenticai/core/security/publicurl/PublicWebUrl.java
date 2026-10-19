package com.agenticai.core.security.publicurl;

/**
 * Helpers around {@code app.web.public-url}, the ONE browser-facing origin of the tenant console
 * (set from {@code APP_WEB_PUBLIC_URL} / {@code PUBLIC_APP_URL}; see application.yml).
 *
 * <p>AGENTS.md rule 4: a browser-facing URL never falls back to {@code localhost}. Every class that
 * builds a link the browser follows (share links, e-mail CTAs, OAuth console redirects) reads the
 * property with an EMPTY default and goes through here, so a missing value is either refused at
 * start-up ({@link PublicWebUrlStartupValidator}, {@code security} profile) or surfaces as a clear
 * {@link IllegalStateException} naming the variable to set, never as a {@code localhost} link in a
 * customer's inbox.
 */
public final class PublicWebUrl {

    /** Property name, as used in application.yml and every {@code @Value} placeholder. */
    public static final String PROPERTY = "app.web.public-url";

    /** Environment variables that feed {@link #PROPERTY}, for messages. */
    public static final String ENV_HINT = "APP_WEB_PUBLIC_URL (Compose) / PUBLIC_APP_URL";

    private PublicWebUrl() {}

    /** True when the value is null or blank (Spring resolves an empty default to {@code ""}). */
    public static boolean isBlank(String appPublicUrl) {
        return appPublicUrl == null || appPublicUrl.isBlank();
    }

    /** The value with every trailing slash removed, or {@code ""} when blank. */
    public static String normalize(String appPublicUrl) {
        return isBlank(appPublicUrl) ? "" : appPublicUrl.trim().replaceAll("/+$", "");
    }

    /**
     * Resolves a console redirect target: the explicitly configured URI when one is set, otherwise
     * {@code <app.web.public-url><path>}, otherwise {@code ""} (the caller must then refuse to
     * redirect - see {@link #require(String, String)}).
     *
     * @param explicitUri the dedicated property (e.g. {@code
     *     app.google.oauth.console-redirect-uri}), may be blank
     * @param appPublicUrl {@code app.web.public-url}, may be blank
     * @param path console path to append when deriving, starting with {@code /}
     */
    public static String consoleRedirect(String explicitUri, String appPublicUrl, String path) {
        if (!isBlank(explicitUri)) {
            return explicitUri.trim();
        }
        String base = normalize(appPublicUrl);
        return base.isEmpty() ? "" : base + path;
    }

    /**
     * Returns {@code value} unless it is blank, in which case an {@link IllegalStateException} that
     * names both the dedicated property and {@link #PROPERTY} is thrown.
     *
     * @param value a URL resolved by {@link #consoleRedirect(String, String, String)}
     * @param propertyName the dedicated property the operator may set instead
     */
    public static String require(String value, String propertyName) {
        if (isBlank(value)) {
            throw new IllegalStateException(
                    "No browser-facing URL configured: set "
                            + PROPERTY
                            + " ("
                            + ENV_HINT
                            + ") or "
                            + propertyName
                            + " - there is deliberately no localhost default (AGENTS.md rule 4)");
        }
        return value;
    }
}
