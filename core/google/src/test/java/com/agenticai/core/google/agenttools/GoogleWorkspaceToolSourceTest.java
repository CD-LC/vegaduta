package com.agenticai.core.google.agenttools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agentspi.GeneratedVideoReader;
import com.agenticai.core.google.GoogleCalendarClient;
import com.agenticai.core.google.GoogleCredential;
import com.agenticai.core.google.GoogleDriveClient;
import com.agenticai.core.google.GoogleGmailClient;
import com.agenticai.core.google.GoogleOAuthService;
import com.agenticai.core.google.GoogleScopes;
import com.agenticai.core.google.GoogleSheetsClient;
import com.agenticai.core.google.YouTubeAnalyticsClient;
import com.agenticai.core.google.YouTubeUploadClient;
import com.agenticai.core.security.HotpathCaches;
import com.agenticai.core.security.HotpathLookups;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The Google tools offered through the kernel's WorkspaceToolSource: none until connected, upload
 * only with its scope and a video backend.
 */
class GoogleWorkspaceToolSourceTest {

    private GoogleOAuthService oauth;
    private GoogleCredential credential;
    private GeneratedVideoReader reader;
    private GoogleWorkspaceToolSource source;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        oauth = mock(GoogleOAuthService.class);
        credential = mock(GoogleCredential.class);
        reader = mock(GeneratedVideoReader.class);
        ObjectProvider<GeneratedVideoReader> readerProvider = mock(ObjectProvider.class);
        when(readerProvider.getIfAvailable()).thenReturn(reader);
        source =
                new GoogleWorkspaceToolSource(
                        oauth,
                        mock(GoogleDriveClient.class),
                        mock(GoogleGmailClient.class),
                        mock(GoogleCalendarClient.class),
                        mock(GoogleSheetsClient.class),
                        mock(YouTubeAnalyticsClient.class),
                        mock(YouTubeUploadClient.class),
                        readerProvider);
    }

    private static List<String> names(List<ToolCallback> tools) {
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
    }

    @Test
    void nothingIsOfferedUntilTheTenantConnectsGoogle() {
        when(oauth.status()).thenReturn(Optional.empty());
        assertTrue(source.rawTools().isEmpty());
    }

    @Test
    void theBaseSetIsOfferedOnceConnected() {
        when(oauth.status()).thenReturn(Optional.of(credential));
        when(oauth.activeCredential()).thenReturn(Optional.of(credential));
        when(credential.hasScope(GoogleScopes.YOUTUBE_UPLOAD)).thenReturn(false);
        when(reader.isAvailable()).thenReturn(true);

        List<String> names = names(source.rawTools());

        assertEquals(8, names.size(), names.toString());
        assertTrue(
                names.containsAll(
                        List.of(
                                "google_drive",
                                "google_gmail",
                                "google_calendar",
                                "google_calendar_find_free_slots",
                                "google_calendar_move_event",
                                "google_calendar_cancel_event",
                                "google_sheets")),
                names.toString());
    }

    @Test
    void uploadNeedsBothTheUploadScopeAndAVideoBackend() {
        when(oauth.status()).thenReturn(Optional.of(credential));
        when(oauth.activeCredential()).thenReturn(Optional.of(credential));
        when(credential.hasScope(GoogleScopes.YOUTUBE_UPLOAD)).thenReturn(true);

        when(reader.isAvailable()).thenReturn(false);
        assertTrue(
                !names(source.rawTools()).contains("upload_video_to_youtube"),
                "no video backend -> no upload tool");

        when(reader.isAvailable()).thenReturn(true);
        assertTrue(names(source.rawTools()).contains("upload_video_to_youtube"));
    }

    @Test
    void theConnectionAnswerIsCachedPerSubjectAndEvictedByAWrite() {
        when(oauth.status()).thenReturn(Optional.of(credential));
        when(oauth.activeCredential()).thenReturn(Optional.of(credential));
        when(credential.hasScope(GoogleScopes.YOUTUBE_UPLOAD)).thenReturn(false);
        TenantContext.set(TenantContext.of("tenant_google", "alice", Set.of()));
        HotpathLookups.configure(Duration.ofSeconds(3));
        try {
            assertEquals(8, source.rawTools().size());
            assertEquals(8, source.rawTools().size());
            verify(oauth, times(1)).status();

            // Another viewer has their own effective credential, so their own answer.
            TenantContext.set(TenantContext.of("tenant_google", "bob", Set.of()));
            source.rawTools();
            verify(oauth, times(2)).status();

            // A write to the credential row evicts the tenant's answers (TenantConfigEviction).
            HotpathCaches.evictTenantConfig("tenant_google");
            source.rawTools();
            verify(oauth, times(3)).status();
        } finally {
            HotpathLookups.configure(Duration.ZERO);
            HotpathCaches.evictTenantConfig(null);
            TenantContext.clear();
        }
    }

    @Test
    void aTtlOfZeroAsksEveryTime() {
        when(oauth.status()).thenReturn(Optional.empty());
        TenantContext.set(TenantContext.of("tenant_google", "alice", Set.of()));
        try {
            source.rawTools();
            source.rawTools();
            verify(oauth, times(2)).status();
        } finally {
            TenantContext.clear();
        }
    }
}
