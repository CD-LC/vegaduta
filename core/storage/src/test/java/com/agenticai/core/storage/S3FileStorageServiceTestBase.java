package com.agenticai.core.storage;

import static org.mockito.Mockito.mock;

import com.agenticai.core.security.TenantContext;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

/**
 * Shared fixture for the {@link S3FileStorageService} test classes (split by operation:
 * {@code S3FileStorageServiceTest}, {@code S3FileStorageServiceRangeAndSizeTest},
 * {@code S3FileStorageServiceBucketTest}).
 *
 * <p>{@link S3FileStorageService} is the one production {@link
 * FileStorageService} - Sarvam's generated audio today, chat file uploads
 * and knowledge-base multi-modal ingestion next, per its own javadoc. Had
 * zero test coverage before this suite despite every one of those callers
 * depending on its error-mapping being right (a caller that only knows how
 * to handle {@link FileStorageException} must never see a raw {@code
 * SdkException} leak through).
 *
 * <p>The constructor builds its own real {@link S3Client} from {@link
 * StorageProperties} (not constructor-injected), so - unlike every other
 * service in this codebase's test suites so far, which all take their
 * collaborators via the constructor - this suite constructs the service for
 * real (safe: {@code S3Client.builder().build()} only builds a client, it
 * never makes a network call) and then replaces the private {@code
 * s3Client} field with a Mockito mock via reflection before exercising any
 * method. This is the one legitimate way to unit-test this class without a
 * real S3/MinIO endpoint (this codebase has no Testcontainers convention -
 * see {@code SchemaNameSanitizerTest}/every other suite here), and {@code
 * @PostConstruct} methods are never invoked by Spring outside a real
 * container, so plain {@code new S3FileStorageService(...)} does not
 * accidentally call {@link S3FileStorageService#ensureBucketExists()} on
 * construction - it's called directly, explicitly, in the tests that target it.
 */
abstract class S3FileStorageServiceTestBase {

    S3Client s3Client;
    S3FileStorageService service;
    java.lang.reflect.Method ensureBucketExists;

    /**
     * Held as a field (not a setUp local) so the legacy-fallback tests below
     * can flip {@code legacyUnprefixedFallback} mid-test - the service reads
     * it per call, not once at construction.
     */
    StorageProperties properties;

    static final String BUCKET = "agentic-ai-test";

    @BeforeEach
    void setUp() throws Exception {
        properties = new StorageProperties();
        properties.setBucket(BUCKET);
        properties.setRegion("us-east-1");
        properties.setAccessKey("test-access-key");
        properties.setSecretKey("test-secret-key");
        properties.setEndpoint("http://localhost:9000");

        service = new S3FileStorageService(properties, 5);

        s3Client = mock(S3Client.class);
        Field field = S3FileStorageService.class.getDeclaredField("s3Client");
        field.setAccessible(true);
        field.set(service, s3Client);

        ensureBucketExists = S3FileStorageService.class.getDeclaredMethod("ensureBucketExists");
        ensureBucketExists.setAccessible(true);
    }

    @AfterEach
    void clearTenantContext() {
        // TenantContext is a ThreadLocal and JUnit reuses the thread across
        // tests - a tenant left set by one test would silently change the
        // physical key every LATER test computes.
        TenantContext.clear();
    }

    /**
     * Builds what {@code S3Client.getObject(GetObjectRequest)} actually returns in AWS
     * SDK v2 (2.28.29, pinned by this module's pom): a {@link ResponseInputStream} over
     * an {@link AbortableInputStream}. Verified against the SDK jars on the classpath -
     * {@code ResponseInputStream} exposes both a {@code (ResponseT, AbortableInputStream)}
     * and a {@code (ResponseT, InputStream)} constructor, and {@code
     * AbortableInputStream.create(InputStream)} is the single-arg factory that wraps a
     * plain stream with a no-op abort. The service under test only ever sees this as an
     * {@code InputStream}, so nothing here depends on the response object's contents.
     */
    static ResponseInputStream<GetObjectResponse> responseStream(InputStream delegate) {
        return new ResponseInputStream<>(
            GetObjectResponse.builder().build(), AbortableInputStream.create(delegate));
    }

    static ResponseInputStream<GetObjectResponse> responseStream(byte[] content) {
        return responseStream(new ByteArrayInputStream(content));
    }

    /** Records how much of the underlying object has actually been pulled off the wire. */
    static final class CountingInputStream extends FilterInputStream {

        private int bytesRead;

        CountingInputStream(byte[] content) {
            super(new ByteArrayInputStream(content));
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                bytesRead++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                bytesRead += n;
            }
            return n;
        }

        int bytesRead() {
            return bytesRead;
        }
    }

    static HeadObjectResponse headResponse(long contentLength) {
        return HeadObjectResponse.builder().contentLength(contentLength).build();
    }
}
