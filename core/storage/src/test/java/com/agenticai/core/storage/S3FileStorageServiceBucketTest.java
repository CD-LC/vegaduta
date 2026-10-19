package com.agenticai.core.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** {@link S3FileStorageService}'s {@code @PostConstruct} bucket bootstrap ({@code ensureBucketExists}). */
class S3FileStorageServiceBucketTest extends S3FileStorageServiceTestBase {

    // --- ensureBucketExists() (@PostConstruct) ----------------------------------

    @Test
    void ensureBucketExistsIsANoOpWhenTheBucketAlreadyExists() throws Exception {
        when(s3Client.headBucket(any(HeadBucketRequest.class))).thenReturn(null);

        ensureBucketExists.invoke(service);

        verify(s3Client, never()).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    void ensureBucketExistsCreatesTheBucketWhenMissing() throws Exception {
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
            .thenThrow(NoSuchBucketException.builder().message("no such bucket").build());

        ensureBucketExists.invoke(service);

        org.mockito.ArgumentCaptor<CreateBucketRequest> captor = org.mockito.ArgumentCaptor.forClass(CreateBucketRequest.class);
        verify(s3Client, times(1)).createBucket(captor.capture());
        assertEquals(BUCKET, captor.getValue().bucket());
    }

    @Test
    void ensureBucketExistsReturnsImmediatelyAndStillChecksWhenBackgroundWarmupIsOn() throws Exception {
        // Cold-start lane: with app.startup.background-warmup.enabled=true the @PostConstruct must not
        // wait for object storage (it can take several seconds when the store is down) but must still
        // perform the same checks, just off the main thread.
        Field flag = S3FileStorageService.class.getDeclaredField("backgroundWarmup");
        flag.setAccessible(true);
        flag.set(service, true);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(s3Client.headBucket(any(HeadBucketRequest.class))).thenAnswer(invocation -> {
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return null;
        });

        ensureBucketExists.invoke(service); // returns while headBucket is still blocked on the latch

        verify(s3Client, org.mockito.Mockito.timeout(2000)).headBucket(any(HeadBucketRequest.class));
        release.countDown();
        verify(s3Client, org.mockito.Mockito.timeout(2000))
            .putBucketLifecycleConfiguration(any(software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest.class));
    }

    @Test
    void ensureBucketExistsDoesNotThrowEvenWhenBucketCreationFails() throws Exception {
        // Documented "best-effort, never fail app startup" posture - a
        // permissions-restricted production bucket rejecting the
        // create-bucket call must not take the Spring context down.
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
            .thenThrow(NoSuchBucketException.builder().message("no such bucket").build());
        when(s3Client.createBucket(any(CreateBucketRequest.class)))
            .thenThrow(S3Exception.builder().message("access denied").build());

        assertDoesNotThrow(() -> {
            try {
                ensureBucketExists.invoke(service);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw (RuntimeException) e.getCause();
            }
        });
    }

    @Test
    void ensureBucketExistsDoesNotThrowWhenObjectStorageIsUnreachableAtStartup() throws Exception {
        // The exact incident this method's javadoc documents fixing: a
        // connection-refused (local MinIO not up yet) must not take the
        // whole Spring context down at boot - see the class's own javadoc
        // for why this catches SdkException broadly, not just S3Exception.
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
            .thenThrow(SdkClientException.create("connection refused"));

        assertDoesNotThrow(() -> {
            try {
                ensureBucketExists.invoke(service);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw (RuntimeException) e.getCause();
            }
        });

        verify(s3Client, never()).createBucket(any(CreateBucketRequest.class));
    }
}
