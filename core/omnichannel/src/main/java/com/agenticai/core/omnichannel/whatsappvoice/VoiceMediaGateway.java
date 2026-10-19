package com.agenticai.core.omnichannel.whatsappvoice;

import java.time.Duration;

/**
 * The WhatsApp media half of a voice-note turn, behind an interface so {@link
 * WhatsAppVoiceNoteService} is tested with a fake instead of Meta. The real one is {@code
 * omnichannel.meta.MetaVoiceMediaGateway}: the channel adapter implements this kernel-owned
 * interface, the kernel never imports the adapter (ADR-0011 rule a).
 */
public interface VoiceMediaGateway {

    /**
     * Downloads one inbound media object into memory. Implementations enforce the size cap and the
     * deadline, never write the bytes anywhere, and throw {@link VoiceNoteException} with a named
     * outcome for every refusal.
     */
    DownloadedMedia download(String accessToken, String mediaId, long maxBytes, Duration timeout);

    /**
     * Sends {@code audio} to {@code to} as a WhatsApp audio message (upload, then send by media
     * id).
     */
    void sendAudio(
            String phoneNumberId,
            String accessToken,
            String to,
            byte[] audio,
            String mimeType,
            String filename);

    /**
     * @param mimeType as reported by Meta for the media object (may be null)
     */
    record DownloadedMedia(byte[] bytes, String mimeType) {}
}
