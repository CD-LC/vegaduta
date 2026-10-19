package com.agenticai.core.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.storage.*} - the only config surface that changes between a local MinIO deployment and
 * production AWS S3/Cloudflare R2. See {@link S3FileStorageService}'s javadoc and this module's
 * pom.xml description for the full reasoning.
 */
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    /**
     * Which {@link FileStorageService} implementation is active: {@code s3} (the default, and the
     * only value before this property existed - an unset property selects it too, so existing
     * deployments are unchanged), {@code azure-blob} ({@link AzureBlobFileStorageService}) or
     * {@code local} ({@link LocalFileStorageService}, a directory on the local filesystem, for
     * single-node/self-hosted "lite" installs that do not run MinIO/S3). Env: {@code
     * APP_STORAGE_TYPE}.
     */
    private String type = "s3";

    /** Settings for {@code type=local}; ignored by the S3 implementation. */
    private final Local local = new Local();

    /** Settings for {@code type=azure-blob}; ignored by the other implementations. */
    private final Azure azure = new Azure();

    /**
     * e.g. {@code http://localhost:9000} for local MinIO, blank for real AWS S3 (SDK resolves the
     * regional endpoint itself), or an R2 account endpoint.
     */
    private String endpoint = "";

    /** Ignored by MinIO but required by the SDK's client builder regardless. */
    private String region = "us-east-1";

    /**
     * Bucket every file this platform stores lives in - created automatically on startup if missing
     * (see {@link S3FileStorageService#ensureBucketExists}).
     */
    private String bucket = "agentic-ai";

    private String accessKey = "";

    private String secretKey = "";

    /**
     * Where the S3 client gets its credentials: {@code static} (default; {@link #accessKey} and
     * {@link #secretKey}, for MinIO/SeaweedFS/R2 and S3 with an IAM user) or {@code default} (the
     * AWS SDK default chain, no keys in the deployment: EKS IRSA / Pod Identity, ECS task role, EC2
     * instance profile, or the standard {@code AWS_*} environment). Use {@code default} for AWS S3;
     * Azure Blob has no S3 API and GCS needs HMAC keys ({@code static}).
     */
    private String credentials = "static";

    /**
     * Path-style addressing ({@code http://host/bucket/key}) - required by MinIO and most
     * self-hosted S3-compatible servers. AWS S3 still accepts it too, but Cloudflare R2 requires it
     * OFF (virtual-hosted style, {@code https://bucket.host/key}) - flip this to false in
     * production when {@link #endpoint} points at an R2 account endpoint.
     */
    private boolean pathStyleAccess = true;

    /**
     * Whether {@link S3FileStorageService#get}/{@link S3FileStorageService#exists} may retry the
     * BARE (un-tenant-prefixed) key after the tenant-scoped key misses - the compatibility path for
     * objects written before tenant prefixing landed (2026-07-27), and for objects a background job
     * writes today with no {@code TenantContext} in scope.
     *
     * <p>Defaults to {@code true} because turning it off 404s any such object. It is exposed as
     * config because the bare-key namespace is GLOBAL - shared by every tenant - so this fallback
     * is the mechanism a cross-tenant read rides on if any call site ever passes an
     * attacker-influenced key (exactly what happened via {@code ChatRequest.attachmentKey},
     * security audit 2026-07-29 §2 item 3). Read access through it is additionally narrowed to a
     * fixed prefix allowlist regardless of this flag; see {@code
     * S3FileStorageService.LEGACY_FALLBACK_PREFIXES}.
     *
     * <p><b>Set this to false</b> once the bucket is known to hold no un-prefixed objects - a
     * deployment whose object storage was provisioned (or wiped) after 2026-07-27 is already in
     * that state and loses nothing by disabling it. Verify with a prefix listing of the bucket root
     * before flipping it; there is no code-side way to check. {@link S3FileStorageService#delete}
     * is deliberately NOT governed by this flag - see its javadoc.
     */
    private boolean legacyUnprefixedFallback = true;

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public String getCredentials() {
        return credentials;
    }

    public void setCredentials(String credentials) {
        this.credentials = credentials == null ? "static" : credentials.trim().toLowerCase();
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public boolean isPathStyleAccess() {
        return pathStyleAccess;
    }

    public void setPathStyleAccess(boolean pathStyleAccess) {
        this.pathStyleAccess = pathStyleAccess;
    }

    public boolean isLegacyUnprefixedFallback() {
        return legacyUnprefixedFallback;
    }

    public void setLegacyUnprefixedFallback(boolean legacyUnprefixedFallback) {
        this.legacyUnprefixedFallback = legacyUnprefixedFallback;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Local getLocal() {
        return local;
    }

    public Azure getAzure() {
        return azure;
    }

    /**
     * {@code app.storage.azure.*} - only read when {@code app.storage.type=azure-blob}. The
     * container is {@link StorageProperties#getBucket()}. With {@code
     * app.storage.credentials=default} the client uses {@code DefaultAzureCredential} (workload
     * identity, managed identity, {@code az login}) against {@link #endpoint}; with {@code static}
     * it uses {@link #connectionString}.
     */
    public static class Azure {

        /**
         * e.g. {@code https://<account>.blob.core.windows.net}. Required with {@code
         * credentials=default}.
         */
        private String endpoint = "";

        /**
         * Full connection string, for {@code credentials=static}: an account key (not recommended
         * outside development) or the Azurite emulator's well-known development string.
         */
        private String connectionString = "";

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getConnectionString() {
            return connectionString;
        }

        public void setConnectionString(String connectionString) {
            this.connectionString = connectionString;
        }
    }

    /** {@code app.storage.local.*} - only read when {@code app.storage.type=local}. */
    public static class Local {

        /**
         * Root directory every object is stored under (created on startup if missing). Env: {@code
         * APP_STORAGE_LOCAL_PATH}. Must be on a persistent volume in a container deployment.
         */
        private String path = "./data/storage";

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        /**
         * SEC-051: whether {@link LocalFileStorageService} may serve the BARE (un-tenant-prefixed)
         * key to a tenant request after the tenant-scoped key misses. {@code
         * app.storage.local.legacy-unprefixed-fallback}, default {@code false}: the bare namespace
         * is global across tenants, and a local store (the OSS/self-host default) has no
         * pre-2026-07-27 un-prefixed objects to be compatible with. Turn it on only for a local
         * root that genuinely holds legacy un-prefixed files - and it still additionally requires
         * the global {@link StorageProperties#isLegacyUnprefixedFallback} and the fixed prefix
         * allowlist.
         */
        private boolean legacyUnprefixedFallback = false;

        public boolean isLegacyUnprefixedFallback() {
            return legacyUnprefixedFallback;
        }

        public void setLegacyUnprefixedFallback(boolean legacyUnprefixedFallback) {
            this.legacyUnprefixedFallback = legacyUnprefixedFallback;
        }
    }
}
