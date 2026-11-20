package com.agenticai.core.sarvam;

/**
 * What the download endpoint needs to serve a stored audio row: type, download name, and either an
 * object-storage key or (legacy pre-V65 rows) the inline base64 payload.
 */
public record SarvamAudioFileRef(
        String contentType, String filename, String objectKey, String audioDataBase64) {}
