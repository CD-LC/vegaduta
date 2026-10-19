package com.agenticai.core.workflow.output;

import com.agenticai.core.agentspi.DriveFileUploader;
import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Output node "google_drive" destination (#156) - reuses the exact same per-tenant Google
 * connection {@code GoogleDriveToolCallback} (agent tools) and the Integrations page already use,
 * via the kernel's {@link DriveFileUploader} (implemented over {@code GoogleDriveClient}, ADR-0011
 * rule a). No separate connect flow: a tenant that has already connected Google under Integrations
 * can use this destination immediately; one that hasn't gets a clear config error pointing at where
 * to connect it, same posture {@link TelegramOutputService} takes for "no bot connected".
 *
 * <p>Scaffold-level scope, matching the task's own framing ("scaffold + setup docs"): writes the
 * run's raw text output as a single Drive file (optionally into a caller-specified folder) via
 * {@link DriveFileUploader#createTextFile(String, String, String)} - no PDF rendering, no
 * multi-file output, no support for native Google Docs conversion. A fuller integration (e.g.
 * rendering the PDF output straight to Drive the way {@code PdfOutputRenderer} does for the "file"
 * download destination) is future work, not blocking this destination from being usable today.
 */
@Service
public class GoogleDriveOutputService {

    private static final Logger log = LoggerFactory.getLogger(GoogleDriveOutputService.class);

    private final DriveFileUploader driveUploader;
    private final WorkflowOutputDedupGuard dedupGuard;

    public GoogleDriveOutputService(
            DriveFileUploader driveUploader, WorkflowOutputDedupGuard dedupGuard) {
        this.driveUploader = driveUploader;
        this.dedupGuard = dedupGuard;
    }

    /**
     * @param runId the workflow run this upload belongs to.
     * @param nodeId the Output node's id within the workflow graph.
     * @return a short, human-readable log line describing what happened, for the run's detailed
     *     trace.
     */
    public String upload(
            String folderId, String fileName, String content, String runId, String nodeId) {
        String resolvedFileName = StringUtils.hasText(fileName) ? fileName : "workflow-output.txt";
        // At-least-once execution can re-run this upload verbatim, leaving two
        // identical files in the tenant's Drive - claim before uploading,
        // release if the upload fails so a retry of a failed upload isn't
        // suppressed. See WorkflowOutputDedupGuard's javadoc for the
        // identity-key's limits.
        String dedupKey = dedupGuard.identityKey("google_drive", runId, nodeId);
        if (!dedupGuard.tryClaim(dedupKey)) {
            log.info(
                    "Suppressed duplicate workflow-output Google Drive upload (dedup key already claimed within TTL).");
            return "Skipped duplicate Google Drive upload of \""
                    + resolvedFileName
                    + "\" - an identical upload already completed moments ago.";
        }
        try {
            DriveFileUploader.UploadedFile file =
                    driveUploader.createTextFile(resolvedFileName, content, folderId);
            return "Uploaded to Google Drive as \""
                    + file.name()
                    + "\" (id "
                    + file.id()
                    + ")"
                    + (StringUtils.hasText(folderId) ? " in folder " + folderId + "." : ".");
        } catch (DriveFileUploader.NotConnectedException e) {
            dedupGuard.release(dedupKey);
            throw new ChannelConfigurationException(
                    "google_drive",
                    "No Google account connected - connect one in Integrations before using this destination.");
        } catch (DriveFileUploader.UploadFailedException e) {
            dedupGuard.release(dedupKey);
            throw new ChannelDeliveryException(
                    "google_drive", "Google Drive API call failed: " + e.getMessage());
        }
    }
}
