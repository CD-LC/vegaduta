package com.agenticai.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobRange;
import com.azure.storage.blob.models.BlobStorageException;
import org.junit.jupiter.api.Test;

/**
 * Backend-specific pieces; the tenant-isolation rules are covered by {@link
 * TenantScopedFileStorageTest}.
 */
class AzureBlobFileStorageServiceTest {

    @Test
    void anInclusiveHttpRangeBecomesAnOffsetAndALength() {
        BlobRange range = AzureBlobFileStorageService.parseRange("bytes=0-0");
        assertThat(range.getOffset()).isEqualTo(0);
        assertThat(range.getCount()).isEqualTo(1);

        range = AzureBlobFileStorageService.parseRange("bytes=100-199");
        assertThat(range.getOffset()).isEqualTo(100);
        assertThat(range.getCount()).isEqualTo(100);
    }

    @Test
    void aMalformedRangeIsRejected() {
        assertThatThrownBy(() -> AzureBlobFileStorageService.parseRange("bytes=5-"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AzureBlobFileStorageService.parseRange("5-9"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingBlobIsNotFoundAndEveryOtherFailureIsABackendFailure() {
        BlobStorageException notFound = mock(BlobStorageException.class);
        when(notFound.getStatusCode()).thenReturn(404);
        assertThat(AzureBlobFileStorageService.translate(notFound))
                .isInstanceOf(TenantScopedFileStorage.ObjectNotFoundException.class);

        BlobStorageException byCode = mock(BlobStorageException.class);
        when(byCode.getStatusCode()).thenReturn(409);
        when(byCode.getErrorCode()).thenReturn(BlobErrorCode.BLOB_NOT_FOUND);
        assertThat(AzureBlobFileStorageService.translate(byCode))
                .isInstanceOf(TenantScopedFileStorage.ObjectNotFoundException.class);

        // S3 parity: a missing container (bucket) is a configuration error, not "no such object".
        BlobStorageException noContainer = mock(BlobStorageException.class);
        when(noContainer.getStatusCode()).thenReturn(404);
        when(noContainer.getErrorCode()).thenReturn(BlobErrorCode.CONTAINER_NOT_FOUND);
        assertThat(AzureBlobFileStorageService.translate(noContainer))
                .isInstanceOf(TenantScopedFileStorage.BackendException.class);

        BlobStorageException forbidden = mock(BlobStorageException.class);
        when(forbidden.getStatusCode()).thenReturn(403);
        when(forbidden.getErrorCode()).thenReturn(BlobErrorCode.AUTHORIZATION_PERMISSION_MISMATCH);
        assertThat(AzureBlobFileStorageService.translate(forbidden))
                .isInstanceOf(TenantScopedFileStorage.BackendException.class);
    }

    @Test
    void staticCredentialsNeedAConnectionString() {
        StorageProperties p = new StorageProperties();
        p.setCredentials("static");
        assertThatThrownBy(() -> AzureBlobFileStorageService.buildContainerClient(p))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connection-string");
    }

    @Test
    void workloadIdentityNeedsTheAccountEndpoint() {
        StorageProperties p = new StorageProperties();
        p.setCredentials("default");
        assertThatThrownBy(() -> AzureBlobFileStorageService.buildContainerClient(p))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.storage.azure.endpoint");
    }

    @Test
    void anUnknownCredentialsModeIsRejected() {
        StorageProperties p = new StorageProperties();
        p.setCredentials("sas");
        assertThatThrownBy(() -> AzureBlobFileStorageService.buildContainerClient(p))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'static' or 'default'");
    }

    @Test
    void workloadIdentityBuildsAClientWithoutAnyStoredKey() {
        StorageProperties p = new StorageProperties();
        p.setCredentials("default");
        p.getAzure().setEndpoint("https://example.blob.core.windows.net");
        p.setBucket("vegaduta");
        // Building the client never calls Azure; the credential is resolved lazily on the first
        // request.
        assertThat(AzureBlobFileStorageService.buildContainerClient(p).getBlobContainerName())
                .isEqualTo("vegaduta");
    }
}
