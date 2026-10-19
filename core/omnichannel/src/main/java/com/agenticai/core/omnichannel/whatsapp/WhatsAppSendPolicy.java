package com.agenticai.core.omnichannel.whatsapp;

import java.util.function.BooleanSupplier;

/**
 * Outbound controls the WhatsApp dispatcher applies to template sends (opt-in records, Meta's
 * per-user marketing limits). Kept free of any dependency on the dispatcher; the implementation
 * lives in {@code whatsapp.policy}. See docs/channels/whatsapp-policy.md.
 */
public interface WhatsAppSendPolicy {

    /**
     * @param isMarketing evaluated only when the recipient has an active backoff
     * @throws WhatsAppSendBlockedException when the send must not happen
     */
    void assertMaySendTemplate(String to, BooleanSupplier isMarketing);

    /** Records a Meta rejection; codes that need no backoff are ignored. */
    void recordRejection(String to, int metaCode);
}
