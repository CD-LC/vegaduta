package com.agenticai.core.workflow.output;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.WhatsAppDispatchService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Output node "whatsapp" destination (#155). Delegates to {@link
 * WhatsAppDispatchService} (omnichannel module) rather than building a
 * Twilio/Meta client itself - that service already owns "which provider is
 * this tenant actually using" (Meta Cloud API if configured, Twilio
 * otherwise; see its own javadoc), so a workflow's Output node and a live
 * chat's manual/auto-reply send ({@code TwilioWhatsAppChannelAdapter}) stay
 * in sync automatically the moment a tenant switches providers - neither
 * has its own copy of that decision to fall out of date.
 *
 * <p>Pulled in as an {@link ObjectProvider} bean dependency, same as {@code
 * KnowledgeSearchService} elsewhere in {@code WorkflowExecutionEngine} -
 * {@code omnichannel} is a real Maven dependency of this module, but the
 * underlying credential (Twilio or Meta) is only meaningfully configured
 * once a tenant has activated one via the Channels page.
 */
@Service
public class WhatsAppOutputService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppOutputService.class);

    private final ObjectProvider<WhatsAppDispatchService> dispatchService;
    private final WorkflowOutputDedupGuard dedupGuard;

    public WhatsAppOutputService(ObjectProvider<WhatsAppDispatchService> dispatchService, WorkflowOutputDedupGuard dedupGuard) {
        this.dispatchService = dispatchService;
        this.dedupGuard = dedupGuard;
    }

    /**
     * @param runId the workflow run this send belongs to.
     * @param nodeId the Output node's id within the workflow graph.
     * @return a short, human-readable log line describing what happened, for the run's detailed trace.
     */
    public String send(String to, String body, String runId, String nodeId) {
        WhatsAppDispatchService service = dispatchService.getIfAvailable();
        if (service == null) {
            throw new ChannelConfigurationException("whatsapp", "The 'omnichannel' profile is not active - WhatsApp is unavailable.");
        }
        if (!StringUtils.hasText(to)) {
            throw new ChannelConfigurationException("whatsapp",
                "Output node has no destination phone number. Set one on the node, or leave it blank and trigger "
                    + "this workflow from an inbound WhatsApp message so the reply goes back to that sender.");
        }
        // At-least-once execution can re-run this send verbatim - claim after
        // config validation, release if the dispatch fails so a retry of a
        // send that never went out isn't suppressed. Keyed by (runId, nodeId,
        // recipient tag) - see dedupKey - so no phone number reaches the dedup
        // key at all. See WorkflowOutputDedupGuard's javadoc for the key's limits.
        String dedupKey = dedupKey(to, runId, nodeId);
        if (!dedupGuard.tryClaim(dedupKey)) {
            log.info("Suppressed duplicate workflow-output WhatsApp send (dedup key already claimed within TTL).");
            return "Skipped duplicate WhatsApp message - an identical send to this recipient already went out moments ago.";
        }
        try {
            return service.send(to, body);
        } catch (RuntimeException e) {
            dedupGuard.release(dedupKey);
            throw e;
        }
    }

    /**
     * Sends an approved Meta template (the only message Meta delivers outside the 24-hour customer
     * window) under the same dedup guard as {@link #send}. With no Meta connection the dispatcher
     * sends {@code fallbackText} through the tenant's other WhatsApp provider instead.
     *
     * @param params       values for the template's {{1}}..{{n}}, in order; may be empty
     * @param fallbackText plain text used when the tenant's provider is not Meta; may be null
     */
    public String sendTemplate(
        String to, String templateName, String languageCode, List<String> params, String fallbackText,
        String runId, String nodeId
    ) {
        WhatsAppDispatchService service = dispatchService.getIfAvailable();
        if (service == null) {
            throw new ChannelConfigurationException("whatsapp", "The 'omnichannel' profile is not active - WhatsApp is unavailable.");
        }
        if (!StringUtils.hasText(to)) {
            throw new ChannelConfigurationException("whatsapp",
                "Output node has no destination phone number. Set one on the node, or leave it blank and trigger "
                    + "this workflow from an inbound WhatsApp message so the reply goes back to that sender.");
        }
        if (!StringUtils.hasText(templateName) || !StringUtils.hasText(languageCode)) {
            throw new ChannelConfigurationException("whatsapp",
                "Output node's WhatsApp template needs both a name and a language.");
        }
        String dedupKey = dedupKey(to, runId, nodeId);
        if (!dedupGuard.tryClaim(dedupKey)) {
            log.info("Suppressed duplicate workflow-output WhatsApp template send (dedup key already claimed within TTL).");
            return "Skipped duplicate WhatsApp message - an identical send to this recipient already went out moments ago.";
        }
        try {
            return service.sendTemplate(to, templateName.strip(), languageCode.strip(), params == null ? List.of() : params, fallbackText);
        } catch (RuntimeException e) {
            dedupGuard.release(dedupKey);
            throw e;
        }
    }

    /**
     * {@code (runId, nodeId, recipient tag)}. The recipient tag (a short SHA-256 prefix, never the
     * number itself) is what lets an Output node used as a loop / fan-out body message a DIFFERENT
     * person on each iteration: those iterations share one runId and nodeId, and before the tag
     * every recipient after the first was suppressed as a "duplicate". A retry of the same node
     * for the same recipient still derives the same key and is still suppressed.
     */
    private String dedupKey(String to, String runId, String nodeId) {
        return dedupGuard.identityKey("whatsapp", runId, nodeId + ":" + recipientTag(to));
    }

    static String recipientTag(String to) {
        try {
            String digits = to == null ? "" : to.replaceAll("[^0-9]", "");
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(digits.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
