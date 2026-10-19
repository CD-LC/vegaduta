package com.agenticai.core.omnichannel.whatsapp;

import java.util.List;
import java.util.Optional;

/**
 * One way a tenant can connect WhatsApp (Meta's Cloud API, Twilio). {@code WhatsAppDispatchService}
 * sends through the first ACTIVE provider by {@link #precedence()}, and the channel status/health
 * screens report that same provider. Kernel-owned (ADR-0011 rule a): implemented in {@code
 * omnichannel.meta} and {@code omnichannel.twilio}, which never see each other.
 */
public interface WhatsAppProvider {

    /** Lower is preferred: Meta (direct, no BSP markup) before Twilio. */
    int precedence();

    /** Short provider name shown on the Channels screen, e.g. {@code "Meta Cloud API"}. */
    String label();

    /** Cheap, non-throwing: the tenant has an ACTIVE credential for this provider. */
    boolean isActive();

    /**
     * @return a short, human-readable log line describing what happened.
     */
    String sendText(String to, String body);

    /**
     * Whether this provider sends approved templates ({@link #sendTemplate}, {@link
     * #listApprovedTemplates}).
     */
    default boolean supportsTemplates() {
        return false;
    }

    /** Only called when {@link #supportsTemplates()}. @return a short log line. */
    default String sendTemplate(
            String to, String templateName, String languageCode, List<String> bodyParams) {
        throw new UnsupportedOperationException(label() + " does not send WhatsApp templates");
    }

    /**
     * Only called when {@link #supportsTemplates()}; empty when nothing is set up (never throws for
     * that).
     */
    default List<WhatsAppTemplate> listApprovedTemplates() {
        return List.of();
    }

    /** The number's quality rating and status as the provider's API reports them. */
    record PhoneHealth(String qualityRating, String status) {}

    /** Empty when the provider exposes no quality rating (Twilio) or is not connected. */
    default Optional<PhoneHealth> phoneHealth() {
        return Optional.empty();
    }

    /**
     * A live, non-billed check of the credential, for the "test connection" card.
     *
     * @return who answered (a phone number or account name), never a secret
     * @throws com.agenticai.core.omnichannel.status.ChannelNotConnectedException when there is no
     *     ACTIVE credential
     */
    String probeIdentity();
}
