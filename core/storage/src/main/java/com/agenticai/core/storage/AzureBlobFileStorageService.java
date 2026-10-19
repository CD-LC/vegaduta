package com.agenticai.core.storage;

import com.agenticai.core.security.startup.BackgroundStartupTask;
import com.azure.core.http.jdk.httpclient.JdkHttpClientBuilder;
import com.azure.core.util.BinaryData;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobRange;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.options.BlobInputStreamOptions;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.azure.storage.common.policy.RequestRetryOptions;
import com.azure.storage.common.policy.RetryPolicyType;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * {@link FileStorageService} on Azure Blob Storage ({@code app.storage.type=azure-blob}): Azure has
 * no S3 API, so it cannot be reached through {@link S3FileStorageService}. Only the physical-key
 * primitives live here; tenant isolation (key prefix, cross-tenant refusal, the legacy fallback) is
 * {@link TenantScopedFileStorage}, shared with S3. Known differences from S3: there is no
 * equivalent of the S3 backend's {@code demo-images/} lifecycle rule (see below), and a read
 * downloads its first chunk eagerly and continues in ETag-conditional chunks.
 *
 * <p><b>No demo-image expiry on Azure:</b> S3 expires {@code demo-images/} objects with a bucket
 * lifecycle rule set over the S3 API. Azure Blob lifecycle management is an account-level policy on
 * the ARM management plane ({@code Microsoft.Storage/storageAccounts/managementPolicies}); the Blob
 * data-plane SDK used here cannot set it, and the application identity should not hold
 * management-plane rights. Create the policy out-of-band (docs/configuration.md, File storage).
 *
 * <p>Auth: {@code app.storage.credentials=default} uses {@code DefaultAzureCredential} (AKS
 * workload identity, managed identity, {@code az login}, environment) against {@code
 * app.storage.azure.endpoint}, so no account key is stored; {@code static} uses {@code
 * app.storage.azure.connection-string}. The container is {@code app.storage.bucket}; it is created
 * on startup when the identity may, as the S3 backend does with its bucket. The JDK HTTP client is
 * used, not Netty, to keep the image small.
 */
@Service
@EnableConfigurationProperties(StorageProperties.class)
@ConditionalOnProperty(name = "app.storage.type", havingValue = "azure-blob")
public class AzureBlobFileStorageService extends TenantScopedFileStorage {

    private static final Logger log = LoggerFactory.getLogger(AzureBlobFileStorageService.class);

    private static final Pattern RANGE = Pattern.compile("^bytes=(\\d+)-(\\d+)$");

    private final BlobContainerClient container;

    /**
     * Same background-warmup switch as the S3 backend: the container check runs off the startup
     * path.
     */
    @Value("${app.startup.background-warmup.enabled:false}")
    private boolean backgroundWarmup;

    // @Autowired is required: with two constructors Spring otherwise looks for a no-arg one.
    @Autowired
    public AzureBlobFileStorageService(StorageProperties properties) {
        super(properties);
        this.container = buildContainerClient(properties);
    }

    /** For tests: use an already built container client. */
    AzureBlobFileStorageService(StorageProperties properties, BlobContainerClient container) {
        super(properties);
        this.container = container;
    }

    static BlobContainerClient buildContainerClient(StorageProperties properties) {
        StorageProperties.Azure azure = properties.getAzure();
        BlobServiceClientBuilder builder =
                new BlobServiceClientBuilder()
                        .httpClient(new JdkHttpClientBuilder().build())
                        // The SDK default (4 tries, exponential from 4 s, 60 s each) blocks startup
                        // for about
                        // a minute against an unreachable account; 3 short tries keep the failure
                        // bounded.
                        .retryOptions(
                                new RequestRetryOptions(
                                        RetryPolicyType.EXPONENTIAL, 3, 30, 500L, 4000L, null));
        if ("default".equals(properties.getCredentials())) {
            if (azure.getEndpoint() == null || azure.getEndpoint().isBlank()) {
                throw new IllegalStateException(
                        "app.storage.azure.endpoint (https://<account>.blob.core.windows.net) is"
                                + " required with app.storage.credentials=default");
            }
            builder.endpoint(azure.getEndpoint())
                    .credential(new DefaultAzureCredentialBuilder().build());
        } else if ("static".equals(properties.getCredentials())) {
            if (azure.getConnectionString() == null || azure.getConnectionString().isBlank()) {
                throw new IllegalStateException(
                        "app.storage.azure.connection-string is required with"
                                + " app.storage.credentials=static (or use"
                                + " app.storage.credentials=default with workload identity)");
            }
            builder.connectionString(azure.getConnectionString());
        } else {
            throw new IllegalStateException(
                    "app.storage.credentials must be 'static' or 'default', not '"
                            + properties.getCredentials()
                            + "'");
        }
        return builder.buildClient().getBlobContainerClient(properties.getBucket());
    }

    @PostConstruct
    void ensureContainerExists() {
        BackgroundStartupTask.run(
                backgroundWarmup, "object-storage-container-check", this::ensureContainerExistsNow);
    }

    void ensureContainerExistsNow() {
        try {
            if (container.createIfNotExists()) {
                log.info("Created object storage container '{}'", properties.getBucket());
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Object storage container '{}' could not be checked or created ({}) - if it is"
                        + " provisioned out-of-band, make sure it exists and the identity may read"
                        + " and write blobs; not fatal to app startup.",
                    properties.getBucket(),
                    e.getMessage());
        }
    }

    // ---- physical-key primitives (the isolation rules are in TenantScopedFileStorage)
    // ------------

    @Override
    protected void writeObject(String physicalKey, byte[] content, String contentType) {
        try {
            BlobParallelUploadOptions options =
                    new BlobParallelUploadOptions(BinaryData.fromBytes(content))
                            .setHeaders(new BlobHttpHeaders().setContentType(contentType));
            container.getBlobClient(physicalKey).uploadWithResponse(options, null, null);
        } catch (RuntimeException e) {
            throw new BackendException(e);
        }
    }

    /** The returned stream holds a connection; the caller closes it. */
    @Override
    protected InputStream readObject(String physicalKey, @Nullable String rangeSpec) {
        try {
            BlobInputStreamOptions options = new BlobInputStreamOptions();
            if (rangeSpec != null) {
                options.setRange(parseRange(rangeSpec));
            }
            return container.getBlobClient(physicalKey).openInputStream(options);
        } catch (BlobStorageException e) {
            throw translate(e);
        } catch (RuntimeException e) {
            throw new BackendException(e);
        }
    }

    @Override
    protected long objectLength(String physicalKey) {
        try {
            return container.getBlobClient(physicalKey).getProperties().getBlobSize();
        } catch (BlobStorageException e) {
            throw translate(e);
        } catch (RuntimeException e) {
            throw new BackendException(e);
        }
    }

    @Override
    protected boolean objectPresent(String physicalKey) {
        try {
            return container.getBlobClient(physicalKey).exists();
        } catch (RuntimeException e) {
            throw new BackendException(e);
        }
    }

    @Override
    protected void removeObject(String physicalKey) {
        try {
            BlobClient blob = container.getBlobClient(physicalKey);
            blob.deleteIfExists();
        } catch (RuntimeException e) {
            throw new BackendException(e);
        }
    }

    @Override
    protected List<String> listTenantIds() {
        List<String> tenantIds = new ArrayList<>();
        try {
            for (BlobItem item :
                    container.listBlobsByHierarchy(
                            "/", new ListBlobsOptions().setPrefix(TENANT_KEY_PREFIX), null)) {
                if (Boolean.TRUE.equals(item.isPrefix())) {
                    String p = item.getName();
                    tenantIds.add(p.substring(TENANT_KEY_PREFIX.length(), p.length() - 1));
                }
            }
        } catch (RuntimeException e) {
            throw new BackendException(e);
        }
        return tenantIds;
    }

    @Override
    protected void listObjects(String physicalPrefix, ObjectListener listener) {
        try {
            for (BlobItem item :
                    container.listBlobs(new ListBlobsOptions().setPrefix(physicalPrefix), null)) {
                var modified =
                        item.getProperties() == null
                                ? null
                                : item.getProperties().getLastModified();
                listener.accept(
                        item.getName(),
                        modified == null ? 0L : modified.toInstant().toEpochMilli());
            }
        } catch (RuntimeException e) {
            throw new BackendException(e);
        }
    }

    // ---- helpers
    // ---------------------------------------------------------------------------------

    /**
     * {@code bytes=start-end} (inclusive end, as {@link FileStorageService#getRange} defines) to a
     * blob range.
     */
    static BlobRange parseRange(String rangeSpec) {
        Matcher m = RANGE.matcher(rangeSpec);
        if (!m.matches()) {
            throw new IllegalArgumentException("Unsupported range '" + rangeSpec + "'");
        }
        long start = Long.parseLong(m.group(1));
        long endInclusive = Long.parseLong(m.group(2));
        return new BlobRange(start, endInclusive - start + 1);
    }

    /**
     * A missing blob is "not found"; a missing container is a backend failure, exactly as the S3
     * backend treats a missing bucket (only {@code NoSuchKey} is not-found there), so a misspelled
     * container or bucket name is visible instead of looking like an ordinary 404. A bare 404 whose
     * error code is not {@code ContainerNotFound} (for example the body-less answer to a HEAD) is a
     * missing blob.
     */
    static RuntimeException translate(BlobStorageException e) {
        if (BlobErrorCode.CONTAINER_NOT_FOUND.equals(e.getErrorCode())) {
            return new BackendException(e);
        }
        if (e.getStatusCode() == 404 || BlobErrorCode.BLOB_NOT_FOUND.equals(e.getErrorCode())) {
            return new ObjectNotFoundException(e);
        }
        return new BackendException(e);
    }
}
