package com.agenticai.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** {@link S3FileStorageService} put/get (incl. the legacy un-prefixed fallback), exists and delete. */
class S3FileStorageServiceTest extends S3FileStorageServiceTestBase {

    // --- put() ---------------------------------------------------------------

    @Test
    void putUploadsTheObjectWithTheGivenContentType() {
        service.put("sarvam-audio/a.wav", "hello".getBytes(), "audio/wav");

        org.mockito.ArgumentCaptor<PutObjectRequest> requestCaptor = org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
        assertEquals(BUCKET, requestCaptor.getValue().bucket());
        assertEquals("sarvam-audio/a.wav", requestCaptor.getValue().key());
        assertEquals("audio/wav", requestCaptor.getValue().contentType());
    }

    @Test
    void putWrapsAnSdkFailureAsAFileStorageException() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
            .thenThrow(S3Exception.builder().message("access denied").build());

        FileStorageException ex = assertThrows(FileStorageException.class,
            () -> service.put("key", "data".getBytes(), "text/plain"));

        assertTrue(ex.getMessage().contains("key"));
        assertTrue(ex.getCause() instanceof S3Exception);
    }

    // --- get() -----------------------------------------------------------------

    @Test
    void getReturnsTheObjectBytesAsAStream() throws Exception {
        byte[] content = "file contents".getBytes(StandardCharsets.UTF_8);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream(content));

        InputStream stream = service.get("some-key");

        assertArrayEquals(content, stream.readAllBytes());
    }

    @Test
    void getHandsBackALazyStreamRatherThanReadingTheWholeObjectUpFront() throws Exception {
        // The whole point of the streaming fix (JVM memory audit, 2026-07-29):
        // get() used to be getObjectAsBytes(...) wrapped in a
        // ByteArrayInputStream, so the ENTIRE object was in heap before the
        // caller had touched the stream. Asserting "zero bytes consumed at the
        // moment get() returns" is what actually pins that down - a buffered
        // implementation cannot pass this, however it dresses up its return type.
        byte[] content = "a payload the caller has not asked for yet".getBytes(StandardCharsets.UTF_8);
        CountingInputStream counting = new CountingInputStream(content);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream(counting));

        InputStream stream = service.get("some-key");

        assertEquals(0, counting.bytesRead(),
            "get() must return a stream the caller drains itself, not a copy already pulled into heap");
        assertArrayEquals(content, stream.readAllBytes());
        assertEquals(content.length, counting.bytesRead());
    }

    @Test
    void getThrowsFileStorageExceptionForANoSuchKey() {
        when(s3Client.getObject(any(GetObjectRequest.class)))
            .thenThrow(NoSuchKeyException.builder().message("not found").build());

        FileStorageException ex = assertThrows(FileStorageException.class, () -> service.get("missing-key"));

        assertTrue(ex.getMessage().contains("No object at key"));
    }

    @Test
    void getWrapsAnyOtherSdkFailureAsAFileStorageExceptionToo() {
        when(s3Client.getObject(any(GetObjectRequest.class)))
            .thenThrow(SdkClientException.create("connection refused"));

        assertThrows(FileStorageException.class, () -> service.get("some-key"));
    }

    @Test
    void getFallsBackToTheUnPrefixedKeyForAnObjectStoredBeforeTenantPrefixing() throws Exception {
        // The "Known limitation - pre-existing, un-prefixed objects" path in
        // the service's own javadoc: the tenant-scoped key misses, so it
        // re-probes the bare logical key rather than 404ing on old data.
        TenantContext.set(TenantContext.of("acme", "user@acme.example", Set.of()));
        byte[] legacy = "uploaded before tenant prefixing existed".getBytes(StandardCharsets.UTF_8);
        when(s3Client.getObject(any(GetObjectRequest.class)))
            .thenThrow(NoSuchKeyException.builder().message("not found").build())
            .thenReturn(responseStream(legacy));

        InputStream stream = service.get("avatars/legacy.png");

        assertArrayEquals(legacy, stream.readAllBytes());
        org.mockito.ArgumentCaptor<GetObjectRequest> captor =
            org.mockito.ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client, times(2)).getObject(captor.capture());
        assertEquals("tenant/acme/avatars/legacy.png", captor.getAllValues().get(0).key());
        assertEquals("avatars/legacy.png", captor.getAllValues().get(1).key());
    }

    @Test
    void getRefusesAFullyQualifiedKeyThatBelongsToAnotherTenant() {
        // Defense-in-depth ownership check - must reject BEFORE any S3 call,
        // so a leaked cross-tenant key never even reaches the bucket.
        TenantContext.set(TenantContext.of("acme", "user@acme.example", Set.of()));

        FileStorageException ex = assertThrows(FileStorageException.class,
            () -> service.get("tenant/globex/avatars/someone-else.png"));

        assertTrue(ex.getMessage().contains("does not belong to the current tenant"));
        verify(s3Client, never()).getObject(any(GetObjectRequest.class));
    }

    // --- legacy un-prefixed fallback, allowlisted (security audit 2026-07-29) ---
    //
    // The bare-key namespace is GLOBAL - no tenant anywhere in the path - so
    // the fallback that makes pre-2026-07-27 objects resolvable is also the
    // mechanism a cross-tenant read rides on. It is now narrowed to a fixed
    // prefix allowlist and can be switched off entirely. These tests use
    // AssertJ (the repo-wide default via spring-boot-starter-test); the older
    // tests above predate that and are left as they are.

    @Test
    void getRefusesTheUnPrefixedFallbackForANamespaceOutsideTheAllowlist() {
        // The audit's exact payload shape: another tenant's full JSON database
        // export, which lives at a bare key. tenant-exports/ is deliberately
        // NOT on the fallback allowlist - nothing in the codebase reads it
        // back, so denying it costs nothing and removes the exploit.
        TenantContext.set(TenantContext.of("acme", "user@acme.example", Set.of()));
        when(s3Client.getObject(any(GetObjectRequest.class)))
            .thenThrow(NoSuchKeyException.builder().message("not found").build());

        assertThatThrownBy(
                () -> service.get("tenant-exports/00000000-0000-4000-8000-000000000001/1753800000000.json"))
            .isInstanceOf(FileStorageException.class)
            .hasMessageContaining("No object at key");

        // Exactly ONE lookup - the tenant-scoped one. The bare key was never
        // probed, so another tenant's object could not have been served.
        org.mockito.ArgumentCaptor<GetObjectRequest> captor =
            org.mockito.ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client, times(1)).getObject(captor.capture());
        assertThat(captor.getValue().key())
            .isEqualTo("tenant/acme/tenant-exports/00000000-0000-4000-8000-000000000001/1753800000000.json");
    }

    @Test
    void getRefusesEveryUnPrefixedFallbackWhenTheFeatureIsSwitchedOff() {
        // The operator escape hatch, for a bucket known to hold no
        // un-prefixed objects: even an allowlisted prefix stops falling back.
        properties.setLegacyUnprefixedFallback(false);
        TenantContext.set(TenantContext.of("acme", "user@acme.example", Set.of()));
        when(s3Client.getObject(any(GetObjectRequest.class)))
            .thenThrow(NoSuchKeyException.builder().message("not found").build());

        assertThatThrownBy(() -> service.get("avatars/legacy.png"))
            .isInstanceOf(FileStorageException.class);

        verify(s3Client, times(1)).getObject(any(GetObjectRequest.class));
    }

    @Test
    void existsDoesNotProbeTheBareKeyForANamespaceOutsideTheAllowlist() {
        // exists() is kept in lockstep with get() on purpose - answering
        // "true" for something get() would refuse is its own (smaller)
        // cross-tenant disclosure.
        TenantContext.set(TenantContext.of("acme", "user@acme.example", Set.of()));
        when(s3Client.headObject(any(HeadObjectRequest.class)))
            .thenThrow(NoSuchKeyException.builder().message("not found").build());

        assertThat(service.exists("tenant-exports/00000000-0000-4000-8000-000000000001/1.json"))
            .isFalse();

        verify(s3Client, times(1)).headObject(any(HeadObjectRequest.class));
    }

    @Test
    void deleteStillAttemptsTheBareKeyEvenForANamespaceTheReadAllowlistExcludes() {
        // Deliberate asymmetry, documented on delete()'s javadoc: erasure must
        // still be able to remove a legacy tenant-exports object, and delete
        // serves no bytes so it cannot leak. Pinned here so a later "make it
        // consistent" refactor has to argue with a failing test first.
        TenantContext.set(TenantContext.of("acme", "user@acme.example", Set.of()));

        service.delete("tenant-exports/00000000-0000-4000-8000-000000000001/1.json");

        org.mockito.ArgumentCaptor<DeleteObjectRequest> captor =
            org.mockito.ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client, times(2)).deleteObject(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(DeleteObjectRequest::key)
            .containsExactly(
                "tenant/acme/tenant-exports/00000000-0000-4000-8000-000000000001/1.json",
                "tenant-exports/00000000-0000-4000-8000-000000000001/1.json"
            );
    }

    @Test
    void getStillFallsBackForEveryAllowlistedLegacyNamespace() {
        // Guards the compatibility half of the decision: the allowlist was
        // derived from the real put/get call sites, and shrinking it silently
        // 404s live files. One representative key per namespace.
        for (String legacyKey : java.util.List.of(
            "avatars/00000000-0000-4000-8000-000000000002.png",
            "chat-attachments/00000000-0000-4000-8000-000000000003/00000000-0000-4000-8000-000000000004",
            "demo-images/img.png",
            "demo-videos/clip.mp4",
            "elevenlabs-audio/00000000-0000-4000-8000-000000000005.mp3",
            "generated-images/00000000-0000-4000-8000-000000000006.png",
            "generated-videos/00000000-0000-4000-8000-000000000007.mp4",
            "knowledge-documents/00000000-0000-4000-8000-000000000008/report.pdf",
            "sarvam-audio/00000000-0000-4000-8000-000000000009.wav"
        )) {
            // One shared mock across nine cases, so its call count and stubbed
            // sequence have to be cleared between them.
            org.mockito.Mockito.reset(s3Client);
            TenantContext.set(TenantContext.of("acme", "user@acme.example", Set.of()));
            byte[] legacy = "pre-prefix bytes".getBytes(StandardCharsets.UTF_8);
            when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build())
                .thenReturn(responseStream(legacy));

            assertThatCode(() -> service.get(legacyKey))
                .as("legacy fallback for %s", legacyKey)
                .doesNotThrowAnyException();
            verify(s3Client, times(2)).getObject(any(GetObjectRequest.class));
        }
    }

    // --- exists() --------------------------------------------------------------

    @Test
    void existsReturnsTrueWhenHeadObjectSucceeds() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(null);

        assertTrue(service.exists("present-key"));
    }

    @Test
    void existsReturnsFalseRatherThanThrowingForANoSuchKey() {
        // Documented "no object at this key" is a normal, expected outcome
        // for exists() - unlike get()/delete(), it must not surface as an
        // exception at all.
        when(s3Client.headObject(any(HeadObjectRequest.class)))
            .thenThrow(NoSuchKeyException.builder().message("not found").build());

        assertFalse(service.exists("missing-key"));
    }

    @Test
    void existsStillThrowsForAGenuineSdkFailureOtherThanNotFound() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
            .thenThrow(SdkClientException.create("network down"));

        assertThrows(FileStorageException.class, () -> service.exists("some-key"));
    }

    // --- delete() ----------------------------------------------------------------

    @Test
    void deleteSucceedsSilentlyMatchingFileDeleteSemantics() {
        assertDoesNotThrow(() -> service.delete("some-key"));

        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void deleteWrapsAnSdkFailureAsAFileStorageException() {
        org.mockito.Mockito.doThrow(S3Exception.builder().message("boom").build())
            .when(s3Client).deleteObject(any(DeleteObjectRequest.class));

        assertThrows(FileStorageException.class, () -> service.delete("some-key"));
    }
}
