package com.agenticai.core.omnichannel.whatsappvoice;

/**
 * A voice-note step failed in a way that has a named {@link VoiceNoteOutcome}. Thrown by the media and
 * speech gateways and translated by {@link WhatsAppVoiceNoteService}; the message must never carry
 * audio, a transcript or a phone number (it can reach logs).
 */
public class VoiceNoteException extends RuntimeException {

    private final VoiceNoteOutcome outcome;

    public VoiceNoteException(VoiceNoteOutcome outcome, String message) {
        super(message);
        this.outcome = outcome;
    }

    public VoiceNoteException(VoiceNoteOutcome outcome, String message, Throwable cause) {
        super(message, cause);
        this.outcome = outcome;
    }

    public VoiceNoteOutcome getOutcome() {
        return outcome;
    }
}
