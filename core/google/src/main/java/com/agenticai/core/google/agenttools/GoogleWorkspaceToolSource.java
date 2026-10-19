package com.agenticai.core.google.agenttools;

import com.agenticai.core.agentspi.GeneratedVideoReader;
import com.agenticai.core.agentspi.WorkspaceToolSource;
import com.agenticai.core.google.GoogleCalendarClient;
import com.agenticai.core.google.GoogleDriveClient;
import com.agenticai.core.google.GoogleGmailClient;
import com.agenticai.core.google.GoogleOAuthService;
import com.agenticai.core.google.GoogleScopes;
import com.agenticai.core.google.GoogleSheetsClient;
import com.agenticai.core.google.YouTubeAnalyticsClient;
import com.agenticai.core.google.YouTubeUploadClient;
import com.agenticai.core.security.HotpathLookups;
import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The Google Workspace tools, contributed through the kernel's {@link WorkspaceToolSource}
 * (ADR-0011 rule a). Lives in the agents module because the tool callbacks do, but in the google
 * vendor package, so it can move out with the google module later.
 *
 * <p>"No manual selection or typing of tools": offered to every agent automatically the moment the
 * TENANT connects a Google account (see {@code GoogleOAuthService}) - there is no per-agent Google
 * toggle. The base set (Drive/Gmail/Calendar/Sheets/YouTube-analytics, plus the three booking tools:
 * calendar free slots, move and cancel, each its own name so moves and cancels are gated apart from
 * reads) is always offered together once connected; scope gaps among those (e.g. Gmail send granted but not Gmail read, or an account
 * connected before {@link GoogleScopes#YOUTUBE_READONLY} was added) surface as a normal 403 from
 * Google inside the tool call rather than being pre-filtered here, since the granted scopes reflect
 * what the user approved on Google's own consent screen.
 *
 * <p><b>{@code upload_video_to_youtube} is the one exception</b>: it needs {@link
 * GoogleScopes#YOUTUBE_UPLOAD}, a separate incremental consent almost no tenant will have granted,
 * so it IS pre-filtered on the granted scope - publishing to a real channel is consequential enough
 * that a tenant who never opted in should never see the tool at all. It also needs a video backend
 * ({@link GeneratedVideoReader#isAvailable()}).
 */
@Component
public class GoogleWorkspaceToolSource implements WorkspaceToolSource {

    private final GoogleOAuthService googleOAuthService;
    private final GoogleDriveClient driveClient;
    private final GoogleGmailClient gmailClient;
    private final GoogleCalendarClient calendarClient;
    private final GoogleSheetsClient sheetsClient;
    private final YouTubeAnalyticsClient youTubeAnalyticsClient;
    private final YouTubeUploadClient youTubeUploadClient;
    private final ObjectProvider<GeneratedVideoReader> videoReader;

    public GoogleWorkspaceToolSource(
            GoogleOAuthService googleOAuthService,
            GoogleDriveClient driveClient,
            GoogleGmailClient gmailClient,
            GoogleCalendarClient calendarClient,
            GoogleSheetsClient sheetsClient,
            YouTubeAnalyticsClient youTubeAnalyticsClient,
            YouTubeUploadClient youTubeUploadClient,
            ObjectProvider<GeneratedVideoReader> videoReader) {
        this.googleOAuthService = googleOAuthService;
        this.driveClient = driveClient;
        this.gmailClient = gmailClient;
        this.calendarClient = calendarClient;
        this.sheetsClient = sheetsClient;
        this.youTubeAnalyticsClient = youTubeAnalyticsClient;
        this.youTubeUploadClient = youTubeUploadClient;
        this.videoReader = videoReader;
    }

    /**
     * What the tool source needs to know about the caller's Google connection, as one immutable
     * value: connected at all, and holding the YouTube upload scope. Built inside the loading
     * transactions, so no JPA entity is cached.
     */
    record Access(boolean connected, boolean canUploadVideo) {
        static final Access NOT_CONNECTED = new Access(false, false);
    }

    /**
     * The connection answer is asked on every turn but changes only when someone connects or
     * disconnects, so it is read through the tenant lookup cache. The key carries the subject
     * because the effective credential is per viewer (a personal row wins over the tenant-wide
     * one). A write to the credential row evicts it on this pod; TTL 0 reads every time.
     */
    private Access access() {
        String subject = TenantContext.current().map(TenantContext::getSubject).orElse("");
        return HotpathLookups.tenant(
                "google.tool-access",
                subject == null ? "" : subject,
                () -> {
                    if (googleOAuthService.status().isEmpty()) {
                        return Access.NOT_CONNECTED;
                    }
                    boolean canUpload =
                            googleOAuthService
                                    .activeCredential()
                                    .map(c -> c.hasScope(GoogleScopes.YOUTUBE_UPLOAD))
                                    .orElse(false);
                    return new Access(true, canUpload);
                });
    }

    @Override
    public List<ToolCallback> rawTools() {
        Access access = access();
        if (!access.connected()) {
            return List.of();
        }
        List<ToolCallback> tools =
                new ArrayList<>(
                        List.of(
                                new GoogleDriveToolCallback(driveClient),
                                new GoogleGmailToolCallback(gmailClient),
                                new GoogleCalendarToolCallback(calendarClient),
                                new GoogleCalendarFreeSlotsToolCallback(calendarClient),
                                GoogleCalendarChangeEventToolCallback.move(calendarClient),
                                GoogleCalendarChangeEventToolCallback.cancel(calendarClient),
                                new GoogleSheetsToolCallback(sheetsClient),
                                new GoogleYouTubeToolCallback(youTubeAnalyticsClient)));
        GeneratedVideoReader reader = videoReader.getIfAvailable();
        if (reader != null && reader.isAvailable() && access.canUploadVideo()) {
            tools.add(new UploadVideoToYouTubeToolCallback(reader, youTubeUploadClient));
        }
        return tools;
    }
}
