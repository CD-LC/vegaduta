package com.agenticai.core.omnichannel.whatsappvoice;

/**
 * Identifies an audio container from its first bytes. Used twice: on the way in (refuse something
 * that is not audio before paying to transcribe it) and on the way out (Sarvam's text-to-speech docs
 * call the {@code audios} payload "WAV" even when another codec is requested, and WhatsApp rejects
 * WAV - so check what actually came back instead of trusting the request).
 *
 * <p>WhatsApp's accepted audio set
 * (https://developers.facebook.com/documentation/business-messaging/whatsapp/messages/audio-messages.md):
 * AAC, AMR, MP3, MP4 audio and OGG with the Opus codec only; WAV is not supported.
 */
final class AudioFormatSniffer {

    enum Kind {
        OGG_OPUS("audio/ogg; codecs=opus", "ogg", true),
        MP3("audio/mpeg", "mp3", true),
        AAC("audio/aac", "aac", true),
        MP4("audio/mp4", "m4a", true),
        AMR("audio/amr", "amr", true),
        WAV("audio/wav", "wav", false),
        UNKNOWN("application/octet-stream", "bin", false);

        private final String mime;
        private final String extension;
        private final boolean whatsAppAccepts;

        Kind(String mime, String extension, boolean whatsAppAccepts) {
            this.mime = mime;
            this.extension = extension;
            this.whatsAppAccepts = whatsAppAccepts;
        }

        String mime() {
            return mime;
        }

        String extension() {
            return extension;
        }

        /** Whether WhatsApp accepts this container (inbound it delivers exactly these; outbound it sends exactly these). */
        boolean whatsAppAccepts() {
            return whatsAppAccepts;
        }
    }

    private AudioFormatSniffer() {
    }

    static Kind sniff(byte[] b) {
        if (b == null || b.length < 4) {
            return Kind.UNKNOWN;
        }
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b.length >= 12
            && b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E') {
            return Kind.WAV;
        }
        if (b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') {
            return OggOpusProbe.isOggOpus(b) ? Kind.OGG_OPUS : Kind.UNKNOWN;
        }
        if (b.length >= 8 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
            return Kind.MP4;
        }
        if (b[0] == '#' && b[1] == '!' && b[2] == 'A' && b[3] == 'M') {
            return Kind.AMR;
        }
        if (b[0] == 'I' && b[1] == 'D' && b[2] == '3') {
            return Kind.MP3;
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0) {
            int layer = (b[1] >> 1) & 0x03;
            // ADTS (AAC) frames set the layer bits to 00; MPEG audio layer III is 01.
            if (layer == 0) {
                return Kind.AAC;
            }
            if (layer == 1) {
                return Kind.MP3;
            }
        }
        return Kind.UNKNOWN;
    }
}
