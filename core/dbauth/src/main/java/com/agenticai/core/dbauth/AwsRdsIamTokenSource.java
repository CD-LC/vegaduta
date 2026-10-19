package com.agenticai.core.dbauth;

import java.time.Clock;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.rds.RdsUtilities;

/**
 * RDS / Aurora IAM database authentication: a SigV4-presigned {@code connect} request used as the
 * password. AWS accepts it for 15 minutes from signing. Credentials come from the SDK default chain
 * (IRSA, EKS Pod Identity, instance profile, environment). Loaded only when {@code
 * app.datasource.auth=aws-iam}.
 */
final class AwsRdsIamTokenSource implements DbTokenSource {

    static final Duration TOKEN_LIFETIME = Duration.ofMinutes(15);

    private final RdsUtilities utilities;
    private final String host;
    private final int port;
    private final String username;
    private final Clock clock;

    private AwsRdsIamTokenSource(
            RdsUtilities utilities, String host, int port, String username, Clock clock) {
        this.utilities = utilities;
        this.host = host;
        this.port = port;
        this.username = username;
        this.clock = clock;
    }

    static DbTokenSource create(String jdbcUrl, String username, String regionOverride) {
        JdbcUrls.Endpoint endpoint = JdbcUrls.endpoint(jdbcUrl);
        Region region =
                regionOverride == null || regionOverride.isBlank()
                        ? new DefaultAwsRegionProviderChain().getRegion()
                        : Region.of(regionOverride.trim());
        RdsUtilities utilities =
                RdsUtilities.builder()
                        .region(region)
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .build();
        return new AwsRdsIamTokenSource(
                utilities, endpoint.host(), endpoint.port(), username, Clock.systemUTC());
    }

    @Override
    public DbToken fetch() {
        String token =
                utilities.generateAuthenticationToken(
                        r -> r.hostname(host).port(port).username(username));
        return new DbToken(token, clock.instant().plus(TOKEN_LIFETIME));
    }
}
