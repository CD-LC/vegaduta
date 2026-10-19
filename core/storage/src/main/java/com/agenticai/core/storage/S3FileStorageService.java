package com.agenticai.core.storage;

import com.agenticai.core.security.startup.BackgroundStartupTask;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * The one production {@link FileStorageService} - talks to whatever S3-compatible endpoint {@link
 * StorageProperties} points at via AWS SDK v2's {@link S3Client}. Same client/config works
 * unmodified against MinIO (local dev - {@code deploy/docker-compose.yml}'s "minio" service),
 * production AWS S3, or Cloudflare R2 - only {@link StorageProperties}' values differ between
 * environments, never this class.
 *
 * <p>{@link #ensureBucketExists()} runs once at startup so a fresh local MinIO instance (or a fresh
 * bucket-per-environment convention in production) doesn't need a separate manual provisioning step
 * before the app can store anything - mirrors this codebase's existing "don't make the person run
 * an extra setup script if the app can safely do it itself" posture (e.g. Flyway migrations
 * auto-applying on boot).
 *
 * <p>Tenant isolation (the {@code tenant/<tenantId>/} key prefix, the cross-tenant ownership check
 * and the allowlisted legacy un-prefixed fallback) lives in {@link TenantScopedFileStorage}, shared
 * with every other object-store backend; this class only implements the physical-key primitives
 * against S3.
 */
@Service
@EnableConfigurationProperties(StorageProperties.class)
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3", matchIfMissing = true)
public class S3FileStorageService extends TenantScopedFileStorage {

    private static final Logger log = LoggerFactory.getLogger(S3FileStorageService.class);

    /**
     * {@code demo-images/} in {@code DemoImageService} - duplicated as a literal here rather than
     * shared via a constant, since {@code storage} is a leaf-ish module and must not take a compile
     * dependency on {@code core/tenancy} just for one string (same "duplicated literal, no shared
     * constant class" trade-off {@code chat-attachments/} etc. already make across modules in this
     * codebase).
     */
    private static final String DEMO_IMAGE_PREFIX = "demo-images/";

    private final S3Client s3Client;
    private final int demoImageLifetimeDays;

    /**
     * Cold-start lane: when {@code app.startup.background-warmup.enabled=true} the best-effort
     * bucket checks in {@link #ensureBucketExists()} run on a background thread instead of blocking
     * context refresh (two network round trips, and several seconds of SDK connect retries when
     * object storage is down). Field-injected so the public constructor is unchanged; false (the
     * default, and the value in every plain {@code new S3FileStorageService(...)}) keeps the old
     * synchronous behaviour exactly.
     */
    @Value("${app.startup.background-warmup.enabled:false}")
    private boolean backgroundWarmup;

    public S3FileStorageService(
            StorageProperties properties,
            // Same key/default DemoImageService/DemoSessionExpiryJob already read -
            // deliberately the SAME property (not a separate app.storage.* one) so
            // the demo-image bucket lifecycle TTL (Part 4) always tracks the demo
            // sandbox's own expiry window instead of two numbers that can drift.
            @Value("${app.demo.lifetime-days:5}") int demoImageLifetimeDays) {
        super(properties);
        this.demoImageLifetimeDays = demoImageLifetimeDays;
        var builder =
                S3Client.builder()
                        .region(Region.of(properties.getRegion()))
                        .credentialsProvider(credentialsProvider(properties))
                        .forcePathStyle(properties.isPathStyleAccess());
        if (properties.getEndpoint() != null && !properties.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }
        this.s3Client = builder.build();
    }

    /**
     * {@code app.storage.credentials=default} uses the AWS SDK default chain (IRSA / Pod Identity,
     * ECS or EC2 role, {@code AWS_*} environment): no long-lived keys in the deployment. Anything
     * else is the configured static key pair. An unknown value is an error, not a silent fallback
     * to keys.
     */
    static AwsCredentialsProvider credentialsProvider(StorageProperties properties) {
        String mode = properties.getCredentials();
        if ("default".equals(mode)) {
            return DefaultCredentialsProvider.builder().build();
        }
        if (!"static".equals(mode)) {
            throw new IllegalStateException(
                    "app.storage.credentials must be 'static' or 'default', not '" + mode + "'");
        }
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey()));
    }

    /**
     * Best-effort - a permissions-restricted production bucket that a platform operator provisions
     * out-of-band (Terraform, the S3/R2 console, etc.) is expected to already exist and reject a
     * create-bucket call; this just logs and moves on rather than failing app startup over it.
     * Local MinIO, by contrast, genuinely benefits from this running: `docker compose up` alone is
     * then enough, no separate `mc mb` step.
     *
     * <p>Catches {@link SdkException} (the common superclass of both {@link S3Exception} - a real
     * S3-modeled error response - and {@link
     * software.amazon.awssdk.core.exception.SdkClientException} - the HTTP connection itself
     * failing, e.g. MinIO not running/not reachable yet), not just {@code S3Exception}. First
     * shipped catching only {@code S3Exception}, which let a connection-refused at startup (local
     * MinIO not up, or a CI/test environment with no object storage at all) propagate out of this
     * {@code @PostConstruct} method uncaught and take the whole Spring context down with it -
     * exactly the "failing app startup over it" this method's own javadoc says it shouldn't do.
     *
     * <p>Also configures the demo-image bucket lifecycle rule (Part 4 of the infra audit fix - see
     * {@link #ensureDemoImageLifecyclePolicy()}) in the same best-effort spirit: a provider/MinIO
     * version that rejects the lifecycle call logs a warning and does not block startup either.
     */
    @PostConstruct
    void ensureBucketExists() {
        BackgroundStartupTask.run(
                backgroundWarmup, "object-storage-bucket-check", this::ensureBucketExistsNow);
    }

    /** The original body of {@link #ensureBucketExists()}, unchanged. */
    void ensureBucketExistsNow() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(properties.getBucket()).build());
        } catch (NoSuchBucketException e) {
            try {
                s3Client.createBucket(
                        CreateBucketRequest.builder().bucket(properties.getBucket()).build());
                log.info("Created object storage bucket '{}'", properties.getBucket());
            } catch (SdkException createError) {
                log.warn(
                        "Object storage bucket '{}' does not exist and could not be auto-created"
                                + " ({}) - if this is a production S3/R2 bucket, provision it"
                                + " out-of-band; if this is local MinIO, check"
                                + " app.storage.access-key/secret-key.",
                        properties.getBucket(),
                        createError.getMessage());
            }
        } catch (SdkException e) {
            log.warn(
                    "Could not reach object storage at startup ({}) - uploads/downloads will fail"
                        + " until this is resolved (e.g. `docker compose up minio`), but the rest"
                        + " of the app keeps starting.",
                    e.getMessage());
        }
        ensureDemoImageLifecyclePolicy();
    }

    /**
     * Part 4 of the infra audit fix - {@code demo-images/} objects (from {@code DemoImageService},
     * the public no-signup "try without an account" sandbox) are created for anonymous visitors on
     * the platform's own dime and have no user-triggered delete path, so without this they
     * accumulate in object storage forever. {@code DemoSessionExpiryJob}'s javadoc documents that a
     * lapsed demo sandbox's Postgres schema is deliberately kept (not dropped) so the "register to
     * keep it" pitch stays honest - this lifecycle rule is the object-storage equivalent for just
     * the generated-image bytes, on the SAME TTL as the sandbox's own expiry window ({@code
     * app.demo.lifetime-days}, default 5), so the platform isn't paying to store sandbox images
     * indefinitely even though the sandbox row itself lives on.
     *
     * <p>Best-effort, same posture as {@link #ensureBucketExists()} itself - a MinIO version or
     * S3-compatible provider that rejects {@code PutBucketLifecycleConfiguration} logs a warning
     * and does not block startup; demo images simply won't auto-expire from storage until that is
     * resolved.
     */
    private void ensureDemoImageLifecyclePolicy() {
        try {
            s3Client.putBucketLifecycleConfiguration(
                    PutBucketLifecycleConfigurationRequest.builder()
                            .bucket(properties.getBucket())
                            .lifecycleConfiguration(
                                    BucketLifecycleConfiguration.builder()
                                            .rules(
                                                    LifecycleRule.builder()
                                                            .id("expire-demo-generated-images")
                                                            .status(ExpirationStatus.ENABLED)
                                                            .filter(
                                                                    LifecycleRuleFilter.builder()
                                                                            .prefix(
                                                                                    DEMO_IMAGE_PREFIX)
                                                                            .build())
                                                            .expiration(
                                                                    LifecycleExpiration.builder()
                                                                            .days(
                                                                                    demoImageLifetimeDays)
                                                                            .build())
                                                            .build())
                                            .build())
                            .build());
            log.info(
                    "Configured object storage lifecycle rule: objects under '{}' expire after {}"
                        + " day(s) (matches app.demo.lifetime-days, the demo sandbox's own expiry"
                        + " window)",
                    DEMO_IMAGE_PREFIX,
                    demoImageLifetimeDays);
        } catch (SdkException e) {
            log.warn(
                    "Could not configure the demo-image bucket lifecycle rule on '{}' ({}) -"
                            + " demo-generated images under '{}' will not auto-expire from object"
                            + " storage; not fatal to app startup.",
                    properties.getBucket(),
                    e.getMessage(),
                    DEMO_IMAGE_PREFIX);
        }
    }

    // ---- physical-key primitives (the isolation rules are in TenantScopedFileStorage)
    // ------------

    @Override
    protected void writeObject(String physicalKey, byte[] content, String contentType) {
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.getBucket())
                            .key(physicalKey)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromBytes(content));
        } catch (SdkException e) {
            throw new BackendException(e);
        }
    }

    /**
     * {@code getObject} returns a {@code ResponseInputStream} that holds an open HTTP connection
     * from the SDK's pool, so the caller MUST close it.
     */
    @Override
    protected InputStream readObject(String physicalKey, @Nullable String rangeSpec) {
        try {
            GetObjectRequest.Builder request =
                    GetObjectRequest.builder().bucket(properties.getBucket()).key(physicalKey);
            if (rangeSpec != null) {
                request.range(rangeSpec);
            }
            return s3Client.getObject(request.build());
        } catch (NoSuchKeyException e) {
            throw new ObjectNotFoundException(e);
        } catch (SdkException e) {
            throw new BackendException(e);
        }
    }

    @Override
    protected long objectLength(String physicalKey) {
        HeadObjectResponse head;
        try {
            head =
                    s3Client.headObject(
                            HeadObjectRequest.builder()
                                    .bucket(properties.getBucket())
                                    .key(physicalKey)
                                    .build());
        } catch (NoSuchKeyException e) {
            throw new ObjectNotFoundException(e);
        } catch (SdkException e) {
            throw new BackendException(e);
        }
        Long contentLength = head.contentLength();
        if (contentLength == null) {
            // S3/MinIO always return Content-Length on HeadObject; a null here means a
            // non-conformant
            // endpoint, which the caller can't do anything sensible with - surface it, don't invent
            // 0.
            throw new FileStorageException(
                    "Object storage returned no size for key '" + physicalKey + "'", null);
        }
        return contentLength;
    }

    @Override
    protected boolean objectPresent(String physicalKey) {
        try {
            s3Client.headObject(
                    HeadObjectRequest.builder()
                            .bucket(properties.getBucket())
                            .key(physicalKey)
                            .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (SdkException e) {
            throw new BackendException(e);
        }
    }

    @Override
    protected void removeObject(String physicalKey) {
        try {
            s3Client.deleteObject(
                    DeleteObjectRequest.builder()
                            .bucket(properties.getBucket())
                            .key(physicalKey)
                            .build());
        } catch (SdkException e) {
            throw new BackendException(e);
        }
    }

    @Override
    protected java.util.List<String> listTenantIds() {
        java.util.List<String> tenantIds = new java.util.ArrayList<>();
        try {
            String token = null;
            do {
                var response =
                        s3Client.listObjectsV2(
                                ListObjectsV2Request.builder()
                                        .bucket(properties.getBucket())
                                        .prefix(TENANT_KEY_PREFIX)
                                        .delimiter("/")
                                        .continuationToken(token)
                                        .build());
                for (var common : response.commonPrefixes()) {
                    String p = common.prefix();
                    tenantIds.add(p.substring(TENANT_KEY_PREFIX.length(), p.length() - 1));
                }
                token = response.isTruncated() ? response.nextContinuationToken() : null;
            } while (token != null);
        } catch (SdkException e) {
            throw new BackendException(e);
        }
        return tenantIds;
    }

    @Override
    protected void listObjects(String physicalPrefix, ObjectListener listener) {
        try {
            String token = null;
            do {
                var response =
                        s3Client.listObjectsV2(
                                ListObjectsV2Request.builder()
                                        .bucket(properties.getBucket())
                                        .prefix(physicalPrefix)
                                        .continuationToken(token)
                                        .build());
                for (var object : response.contents()) {
                    listener.accept(
                            object.key(),
                            object.lastModified() == null
                                    ? 0L
                                    : object.lastModified().toEpochMilli());
                }
                token = response.isTruncated() ? response.nextContinuationToken() : null;
            } while (token != null);
        } catch (SdkException e) {
            throw new BackendException(e);
        }
    }
}
