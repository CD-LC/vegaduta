package com.agenticai.core.google.agenttools;

import com.agenticai.core.agentspi.DriveFileUploader;
import com.agenticai.core.google.GoogleApiException;
import com.agenticai.core.google.GoogleDriveClient;
import com.agenticai.core.google.GoogleNotConnectedException;
import org.springframework.stereotype.Component;

/** The kernel's {@link DriveFileUploader} over the tenant's connected Google Drive. */
@Component
public class GoogleDriveFileUploader implements DriveFileUploader {

    private final GoogleDriveClient driveClient;

    public GoogleDriveFileUploader(GoogleDriveClient driveClient) {
        this.driveClient = driveClient;
    }

    @Override
    public UploadedFile createTextFile(String name, String content, String folderId) {
        try {
            GoogleDriveClient.DriveFile file = driveClient.createTextFile(name, content, folderId);
            return new UploadedFile(file.id(), file.name());
        } catch (GoogleNotConnectedException e) {
            throw new NotConnectedException(e);
        } catch (GoogleApiException e) {
            throw new UploadFailedException(e.getMessage(), e);
        }
    }
}
