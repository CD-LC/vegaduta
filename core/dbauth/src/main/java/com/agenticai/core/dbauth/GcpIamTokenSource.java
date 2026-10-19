package com.agenticai.core.dbauth;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;

/**
 * Cloud SQL IAM database authentication: an OAuth2 access token for the service account (or user)
 * used as the password. Credentials come from Application Default Credentials (GKE Workload
 * Identity, metadata server, {@code GOOGLE_APPLICATION_CREDENTIALS}). The database user is the IAM
 * principal, for a service account the email without {@code .gserviceaccount.com}. Loaded only when
 * {@code app.datasource.auth=gcp-iam}.
 */
final class GcpIamTokenSource implements DbTokenSource {

    /**
     * Used when the library reports no expiry; Google access tokens last an hour, this is
     * deliberately short.
     */
    private static final Duration ASSUMED_LIFETIME = Duration.ofMinutes(5);

    private final GoogleCredentials credentials;
    private final Clock clock;

    private GcpIamTokenSource(GoogleCredentials credentials, Clock clock) {
        this.credentials = credentials;
        this.clock = clock;
    }

    static DbTokenSource create(String scope) {
        try {
            return new GcpIamTokenSource(
                    GoogleCredentials.getApplicationDefault().createScoped(scope),
                    Clock.systemUTC());
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "No Google Application Default Credentials for Cloud SQL IAM login", e);
        }
    }

    @Override
    public DbToken fetch() {
        try {
            credentials.refresh();
        } catch (IOException e) {
            throw new UncheckedIOException("Google access token refresh failed", e);
        }
        AccessToken token = credentials.getAccessToken();
        if (token == null) {
            throw new IllegalStateException("Google credentials returned no access token");
        }
        Date expiry = token.getExpirationTime();
        return new DbToken(
                token.getTokenValue(),
                expiry == null ? clock.instant().plus(ASSUMED_LIFETIME) : expiry.toInstant());
    }
}
