package com.agenticai.core.omnichannel;

import com.agenticai.core.omnichannel.whatsapp.WhatsAppProvider;
import com.agenticai.core.omnichannel.whatsapp.WhatsAppTemplate;
import com.agenticai.core.omnichannel.whatsapp.WhatsAppSendPolicy;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The single place that decides "which of this tenant's connected WhatsApp channels does an
 * outbound send actually go through" and performs the send either way - both {@code
 * TwilioWhatsAppChannelAdapter} (live chat / manual send, HITL-gated) and the workflow module's
 * {@code WhatsAppOutputService} (Output node destination) delegate here instead of each
 * independently re-implementing "check which credential is active."
 *
 * <p><b>Provider preference, stated plainly</b>: the first ACTIVE {@link WhatsAppProvider} by
 * precedence - Meta first (direct-to-Meta is lower latency and has no BSP markup), then Twilio.
 * Both are official WhatsApp Business Platform paths. A tenant is not expected to configure both at
 * once; whichever comes first in that order wins - no separate "provider" toggle to manage in the
 * UI. The providers live in the channel packages (ADR-0011 rule a); a disconnected provider simply
 * reports inactive and a send fails with a clear configuration error.
 */
@Service
public class WhatsAppDispatchService {

    private final List<WhatsAppProvider> providers;
    private final WhatsAppSendPolicy sendGuard;

    public WhatsAppDispatchService(List<WhatsAppProvider> providers, WhatsAppSendPolicy sendGuard) {
        this.sendGuard = sendGuard;
        this.providers =
                providers.stream()
                        .sorted(Comparator.comparingInt(WhatsAppProvider::precedence))
                        .toList();
    }

    /** The provider the next send goes through, if any is connected. */
    public Optional<WhatsAppProvider> activeProvider() {
        return providers.stream().filter(WhatsAppProvider::isActive).findFirst();
    }

    /**
     * True when {@link #send} would find a provider (Meta or Twilio) for the current tenant. Cheap
     * and non-throwing by contract of the two checks it delegates to - used to tell an author
     * "connect WhatsApp first" before a run finds out the hard way.
     */
    public boolean isAnyProviderConnected() {
        return activeProvider().isPresent();
    }

    /**
     * @return a short, human-readable log line describing what happened, for callers that surface a
     *     trace (e.g. workflow run logs).
     */
    public String send(String to, String body) {
        com.agenticai.core.security.ImpersonationContext.assertNotImpersonating("whatsapp-send");
        Optional<WhatsAppProvider> provider = activeProvider();
        if (provider.isPresent()) {
            return provider.get().sendText(to, body);
        }

        throw new ChannelConfigurationException(
                "whatsapp",
                "No WhatsApp channel is connected for this tenant - connect Meta (WhatsApp Cloud"
                        + " API) or Twilio first.");
    }

    /**
     * Sends an approved template. Templates are a Meta Cloud API feature, so the provider order is
     * different from {@link #send}: Meta sends the template; with no Meta connection the tenant's
     * other provider (Twilio) sends {@code fallbackText} as an ordinary message instead - and with
     * neither a Meta connection nor a fallback text there is nothing sendable.
     *
     * @param bodyParams values for the template's {{1}}..{{n}}, in order; may be empty
     * @param fallbackText plain text for non-Meta providers; may be null/blank
     * @return a short, human-readable log line describing what happened
     */
    public String sendTemplate(
            String to,
            String templateName,
            String languageCode,
            List<String> bodyParams,
            String fallbackText) {
        com.agenticai.core.security.ImpersonationContext.assertNotImpersonating("whatsapp-send");
        Optional<WhatsAppProvider> templates = templateProvider();
        if (templates.isPresent()) {
            // Opt-in is required for every business-initiated template; a recipient Meta has
            // capped or told us to stop marketing to is skipped for marketing templates.
            // A category that cannot be looked up is treated as marketing (the stricter reading).
            sendGuard.assertMaySendTemplate(
                    to, () -> isMarketingTemplate(templates.get(), templateName, languageCode));
            try {
                return templates.get().sendTemplate(to, templateName, languageCode, bodyParams);
            } catch (WhatsAppDeliveryRejectedException e) {
                sendGuard.recordRejection(to, e.getMetaCode());
                throw e;
            }
        }
        if (!StringUtils.hasText(fallbackText)) {
            throw new ChannelConfigurationException(
                    "whatsapp",
                    "Approved templates are sent through the Meta WhatsApp Cloud API, which is not"
                        + " connected for this tenant - connect it, or give this step a message to"
                        + " send as plain text instead.");
        }
        return send(to, fallbackText);
    }

    /**
     * The approved templates of the tenant's Meta WhatsApp Business Account; empty when Meta is not
     * the connected provider or the credential carries no WABA id (never throws for "not set up").
     */
    public List<WhatsAppTemplate> listApprovedTemplates() {
        return templateProvider().map(WhatsAppProvider::listApprovedTemplates).orElse(List.of());
    }

    private static boolean isMarketingTemplate(
            WhatsAppProvider provider, String name, String language) {
        try {
            return provider.listApprovedTemplates().stream()
                    .filter(t -> t.name().equals(name) && t.language().equals(language))
                    .findFirst()
                    .map(t -> "MARKETING".equalsIgnoreCase(t.category()))
                    .orElse(true);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** The connected provider that sends approved templates (Meta's Cloud API), if any. */
    private Optional<WhatsAppProvider> templateProvider() {
        return providers.stream().filter(p -> p.isActive() && p.supportsTemplates()).findFirst();
    }
}
