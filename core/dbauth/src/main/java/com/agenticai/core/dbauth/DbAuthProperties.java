package com.agenticai.core.dbauth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.datasource.*}. {@code auth} is also read directly from the environment by the
 * conditions in {@link DbAuthConfiguration}, because they run before this bean exists.
 */
@ConfigurationProperties(prefix = "app.datasource")
public class DbAuthProperties {

    /** password (default) | aws-iam | azure-entra | gcp-iam. */
    private String auth = "password";

    /** Fetch a new token this long before the cached one expires. */
    private Duration tokenRefreshSkew = Duration.ofMinutes(2);

    private final Aws aws = new Aws();
    private final Azure azure = new Azure();
    private final Gcp gcp = new Gcp();

    public String getAuth() {
        return auth;
    }

    public void setAuth(String auth) {
        this.auth = auth;
    }

    public Duration getTokenRefreshSkew() {
        return tokenRefreshSkew;
    }

    public void setTokenRefreshSkew(Duration tokenRefreshSkew) {
        this.tokenRefreshSkew = tokenRefreshSkew;
    }

    public Aws getAws() {
        return aws;
    }

    public Azure getAzure() {
        return azure;
    }

    public Gcp getGcp() {
        return gcp;
    }

    public static class Aws {
        /**
         * Region of the RDS instance; blank uses the SDK chain (AWS_REGION, profile, instance
         * metadata).
         */
        private String region = "";

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }
    }

    public static class Azure {
        /**
         * OAuth scope of the token; the default is the public-cloud Azure Database for PostgreSQL
         * scope.
         */
        private String scope = "https://ossrdbms-aad.database.windows.net/.default";

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }
    }

    public static class Gcp {
        /** OAuth scope of the access token used as the Cloud SQL IAM password. */
        private String scope = "https://www.googleapis.com/auth/sqlservice.login";

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }
    }
}
