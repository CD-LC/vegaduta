package com.agenticai.core.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** {@link S3FileStorageService#size} and {@link S3FileStorageService#getRange}. */
class S3FileStorageServiceRangeAndSizeTest extends S3FileStorageServiceTestBase {

    // --- size() ------------------------------------------------------------------
    //
    // Added 2026-08-04 (edge-capabilities plan item X-2) so HTTP callers can
    // honour open-ended/suffix Range requests. Deliberately kept in lockstep
    // with get(): same tenant prefixing, same allowlisted legacy fallback,
    // same cross-tenant refusal - a measurable size for an object get() would
    // refuse to serve is a (small) cross-tenant disclosure of its own.


    @Test
    void sizeReturnsTheHeadObjectContentLengthAtTheTenantScopedKey() {
        TenantContext.set(TenantContext.of("acme", "user@acme.example", Set.of()));
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(headResponse(1234L));

        assertEquals(1234L, service.size("generated-videos/clip.mp4"));

        org.mockito.ArgumentCaptor<HeadObjectRequest> captor =
            org.mockito.ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(s3Client).headObject(captor.capture());
        assertEquals("tenant/acme/generated-videos/clip.mp4", captor.getValue().key());
    }

    @Test
    void sizeFallsBackToTheUnPrefixedKeyForAnAllowlistedLegacyNamespace() {
        TenantContext.set(TenantContext.of("acme", "user@acme.example", Set.of()));
        when(s3Client.headObject(any(HeadObjectRequest.class)))
            .thenThrow(NoSuchKeyException.builder().message("not found").build())
            .thenReturn(headResponse(777L));

        assertEquals(777L, service.size("generated-videos/legacy.mp4"));

        org.mockito.ArgumentCaptor<HeadObjectRequest> captor =
            org.mockito.ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(s3Client, times(2)).headObject(captor.capture());
        assertEquals("tenant/acme/generated-videos/legacy.mp4", captor.getAllValues().get(0).key());
        assertEquals("generated-videos/legacy.mp4", captor.getAllValues().get(1).key());
    }

    @Test
    void sizeRefusesTheUnPrefixedFallbackForANamespaceOutsideTheAllowlist() {
        // Lockstep with get(): the 2026-07-29 audit's allowlist must gate
        // every read-shaped operation, and "how big is another tenant's
        // export" is itself a disclosure.
        TenantContext.set(TenantContext.of("acme", "user@acme.example", Set.of()));
        when(s3Client.headObject(any(HeadObjectRequest.class)))
            .thenThrow(NoSuchKeyException.builder().message("not found").build());

        assertThatThrownBy(
                () -> service.size("tenant-exports/00000000-0000-4000-8000-000000000001/1.json"))
            .isInstanceOf(FileStorageException.class)
            .hasMessageContaining("No object at key");

        verify(s3Client, times(1)).headObject(any(HeadObjectRequest.class));
    }

    @Test
    void sizeThrowsFileStorageExceptionForAMissingObject() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
            .thenThrow(NoSuchKeyException.builder().message("not found").build());

        assertThatThrownBy(() -> service.size("missing-key"))
            .isInstanceOf(FileStorageException.class)
            .hasMessageContaining("No object at key");
    }

    @Test
    void sizeWrapsAnyOtherSdkFailureAsAFileStorageException() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
            .thenThrow(SdkClientException.create("network down"));

        assertThrows(FileStorageException.class, () -> service.size("some-key"));
    }

    @Test
    void sizeRefusesAFullyQualifiedKeyThatBelongsToAnotherTenant() {
        TenantContext.set(TenantContext.of("acme", "user@acme.example", Set.of()));

        assertThatThrownBy(() -> service.size("tenant/globex/generated-videos/other.mp4"))
            .isInstanceOf(FileStorageException.class)
            .hasMessageContaining("does not belong to the current tenant");
        verify(s3Client, never()).headObject(any(HeadObjectRequest.class));
    }

    // --- getRange() --------------------------------------------------------------

    @Test
    void getRangeRequestsExactlyTheClosedByteIntervalFromS3() throws Exception {
        // The whole point over get()+skip: the Range header goes to S3, so
        // only the requested window ever crosses the wire.
        TenantContext.set(TenantContext.of("acme", "user@acme.example", Set.of()));
        byte[] window = "cdef".getBytes(StandardCharsets.UTF_8);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream(window));

        InputStream stream = service.getRange("generated-videos/clip.mp4", 2, 5);

        assertArrayEquals(window, stream.readAllBytes());
        org.mockito.ArgumentCaptor<GetObjectRequest> captor =
            org.mockito.ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(captor.capture());
        assertEquals("tenant/acme/generated-videos/clip.mp4", captor.getValue().key());
        assertEquals("bytes=2-5", captor.getValue().range());
    }

    @Test
    void getRangeFallsBackToTheUnPrefixedKeyWithTheSameRangeForALegacyObject() throws Exception {
        TenantContext.set(TenantContext.of("acme", "user@acme.example", Set.of()));
        byte[] window = "01".getBytes(StandardCharsets.UTF_8);
        when(s3Client.getObject(any(GetObjectRequest.class)))
            .thenThrow(NoSuchKeyException.builder().message("not found").build())
            .thenReturn(responseStream(window));

        InputStream stream = service.getRange("generated-videos/legacy.mp4", 0, 1);

        assertArrayEquals(window, stream.readAllBytes());
        org.mockito.ArgumentCaptor<GetObjectRequest> captor =
            org.mockito.ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client, times(2)).getObject(captor.capture());
        assertEquals("tenant/acme/generated-videos/legacy.mp4", captor.getAllValues().get(0).key());
        assertEquals("bytes=0-1", captor.getAllValues().get(0).range());
        assertEquals("generated-videos/legacy.mp4", captor.getAllValues().get(1).key());
        assertEquals("bytes=0-1", captor.getAllValues().get(1).range());
    }

    @Test
    void getRangeRefusesTheUnPrefixedFallbackForANamespaceOutsideTheAllowlist() {
        TenantContext.set(TenantContext.of("acme", "user@acme.example", Set.of()));
        when(s3Client.getObject(any(GetObjectRequest.class)))
            .thenThrow(NoSuchKeyException.builder().message("not found").build());

        assertThatThrownBy(
                () -> service.getRange("tenant-exports/00000000-0000-4000-8000-000000000001/1.json", 0, 10))
            .isInstanceOf(FileStorageException.class)
            .hasMessageContaining("No object at key");

        verify(s3Client, times(1)).getObject(any(GetObjectRequest.class));
    }

    @Test
    void getRangeRejectsAnInvalidIntervalBeforeTouchingS3() {
        assertThrows(IllegalArgumentException.class, () -> service.getRange("k", -1, 5));
        assertThrows(IllegalArgumentException.class, () -> service.getRange("k", 10, 5));
        verify(s3Client, never()).getObject(any(GetObjectRequest.class));
    }

    @Test
    void getRangeWrapsAnSdkFailureAsAFileStorageException() {
        // Includes S3's own InvalidRange (416) response - callers derive the
        // interval from size(), so it surfaces like any other storage failure.
        when(s3Client.getObject(any(GetObjectRequest.class)))
            .thenThrow(S3Exception.builder().message("InvalidRange").build());

        assertThrows(FileStorageException.class, () -> service.getRange("some-key", 0, 10));
    }

    @Test
    void getRangeRefusesAFullyQualifiedKeyThatBelongsToAnotherTenant() {
        TenantContext.set(TenantContext.of("acme", "user@acme.example", Set.of()));

        assertThatThrownBy(() -> service.getRange("tenant/globex/generated-videos/other.mp4", 0, 10))
            .isInstanceOf(FileStorageException.class)
            .hasMessageContaining("does not belong to the current tenant");
        verify(s3Client, never()).getObject(any(GetObjectRequest.class));
    }
}
