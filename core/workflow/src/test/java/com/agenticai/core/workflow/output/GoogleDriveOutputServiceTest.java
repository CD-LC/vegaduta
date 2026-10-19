package com.agenticai.core.workflow.output;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.gateway.GovernanceDegradationSignal;
import com.agenticai.core.google.GoogleApiException;
import com.agenticai.core.google.GoogleDriveClient;
import com.agenticai.core.google.agenttools.GoogleDriveFileUploader;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * F-17: {@link GoogleDriveOutputService} must be safe under at-least-once workflow execution - a
 * retried Output node must not leave two identical files in the tenant's Drive. Uses a REAL {@link
 * WorkflowOutputDedupGuard} over a mocked Redis so the fail-open guarantee is exercised end to end,
 * same approach as {@link EmailOutputServiceTest}.
 */
class GoogleDriveOutputServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private GoogleDriveClient driveClient;
    private GoogleDriveOutputService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        WorkflowOutputDedupGuard guard =
                new WorkflowOutputDedupGuard(
                        redisTemplate, 10L, mock(GovernanceDegradationSignal.class));
        driveClient = mock(GoogleDriveClient.class);
        when(driveClient.createTextFile(anyString(), anyString(), any()))
                .thenReturn(
                        new GoogleDriveClient.DriveFile(
                                "file-1", "report.txt", "text/plain", "2026-08-07T10:00:00Z"));
        service = new GoogleDriveOutputService(new GoogleDriveFileUploader(driveClient), guard);
    }

    @Test
    void firstUploadGoesOutAndClaimsTheDedupKey() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(true);

        String result = service.upload("folder-1", "report.txt", "the content", "run-1", "node-1");

        assertTrue(result.startsWith("Uploaded to Google Drive"));
        verify(driveClient).createTextFile("report.txt", "the content", "folder-1");
    }

    @Test
    void duplicateUploadIsSuppressedWithoutTouchingDriveAndReturnsTheCallersSuccessShape() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(false);

        String result = service.upload("folder-1", "report.txt", "the content", "run-1", "node-1");

        assertTrue(result.contains("Skipped duplicate Google Drive upload"));
        verify(driveClient, never()).createTextFile(anyString(), anyString(), any());
    }

    @Test
    void uploadsAnywayWhenRedisIsDown() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        service.upload("folder-1", "report.txt", "the content", "run-1", "node-1");

        verify(driveClient).createTextFile("report.txt", "the content", "folder-1");
    }

    @Test
    void distinctRunsOrNodesDeriveDistinctKeysAndBothUpload() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(true);

        service.upload("folder-1", "report.txt", "content one", "run-1", "node-1");
        service.upload("folder-1", "report.txt", "content one", "run-1", "node-2");

        verify(driveClient, times(2)).createTextFile(anyString(), anyString(), any());
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(2)).setIfAbsent(keys.capture(), eq("1"), any(Duration.class));
        assertNotEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
    }

    @Test
    void aRetryWithDriftedContentButTheSameRunAndNodeIsStillSuppressed() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(true, false);

        service.upload("folder-1", "report.txt", "content one", "run-1", "node-1");
        String secondResult =
                service.upload(
                        "folder-1", "report.txt", "content one - drifted", "run-1", "node-1");

        assertTrue(secondResult.contains("Skipped duplicate Google Drive upload"));
        verify(driveClient, times(1)).createTextFile(anyString(), anyString(), any());
    }

    @Test
    void aFailedUploadReleasesTheClaimSoARetryIsNotSuppressed() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(true);
        when(driveClient.createTextFile(anyString(), anyString(), any()))
                .thenThrow(new GoogleApiException("Drive 500"));

        assertThrows(
                ChannelDeliveryException.class,
                () -> service.upload("folder-1", "report.txt", "the content", "run-1", "node-1"));

        ArgumentCaptor<String> claimedKey = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(claimedKey.capture(), eq("1"), any(Duration.class));
        verify(redisTemplate).delete(claimedKey.getValue());
    }
}
