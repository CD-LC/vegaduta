package com.agenticai.core.agents.job.trigger;

import com.agenticai.core.eventbus.WorkflowEventTypes;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The ALLOW-LIST of event types an agent may subscribe to, and - just as
 * importantly - the payload fields each one actually carries.
 *
 * <p><b>Why an allow-list and not "any string".</b> Two independent reasons,
 * both load-bearing:
 *
 * <ol>
 *   <li><b>Idempotency.</b> The exact dedupe key is {@code eventType|runId}
 *       (see {@link AgentEventTriggerFiring}). For these ten, {@code runId}
 *       identifies ONE event - it is minted per publish for the channel types
 *       and is the document job's id for document generation. The {@code
 *       workflow.*} literals the execution engine also publishes reuse one
 *       {@code runId} across every event of a single RUN, so keying on it there
 *       would collapse a whole run into one firing and silently drop the rest.
 *       {@link com.agenticai.core.eventbus.WorkflowEvent} has no event-id field
 *       of its own and this PR does not invent one.
 *
 *       <p><b>Being minted per publish is NOT the same as being stable across a
 *       redelivery, and the difference is money.</b> A retried webhook re-enters
 *       its controller and calls {@code publishNewRun} again, which mints a
 *       DIFFERENT UUID - so for the nine channel types the exact key cannot
 *       recognise a duplicate delivery at all. Only {@code
 *       document.generation.completed} is exactly-once by this key. The other
 *       nine are covered by the windowed payload fingerprint instead (see
 *       {@code AgentEventTriggerDispatcher#fingerprintOf} and {@code
 *       app.agent-jobs.trigger.duplicate-window}).</li>
 *   <li><b>Templates and filters can be validated at configuration time.</b> An
 *       operator writing <code>{{payload.messageBody}}</code> finds out at
 *       {@code PUT} time that no such field exists, rather than at 3am when a
 *       job's goal turns out to say {@code (not provided)}.</li>
 * </ol>
 *
 * <p><b>The payload field lists below are read off the actual publishers, not
 * imagined.</b> Every channel publisher passes exactly {@code {channel,
 * fromMasked, bodyLength}} (the two WhatsApp publishers that are not Twilio add
 * {@code provider}), and document generation passes {@code {jobId, tenantId,
 * success}}. There is deliberately NO message body and NO unmasked sender
 * anywhere in a channel payload - see {@code TwilioWebhookController}'s comment
 * for why - so a trigger goal cannot contain what the stranger actually wrote.
 * That is a privacy property of the event bus, not something this PR chose, and
 * it is documented rather than worked around.
 */
public final class AgentTriggerEventTypes {

    /** {@code {channel, fromMasked, bodyLength}} - every inbound-channel publisher. */
    private static final Set<String> CHANNEL_FIELDS = ordered("channel", "fromMasked", "bodyLength");

    /**
     * WhatsApp additionally carries {@code provider} - but only from the Meta
     * publisher, NOT from Twilio's. A template referencing it therefore
     * renders {@code (not provided)} for a Twilio-delivered message, which is
     * visible in the job's goal rather than silently blank.
     */
    private static final Set<String> WHATSAPP_FIELDS = ordered("channel", "fromMasked", "bodyLength", "provider");

    private static final Map<String, Set<String>> SUBSCRIBABLE = build();

    private AgentTriggerEventTypes() {
    }

    private static Map<String, Set<String>> build() {
        Map<String, Set<String>> types = new LinkedHashMap<>();
        types.put(WorkflowEventTypes.CHANNEL_WHATSAPP_MESSAGE_RECEIVED, WHATSAPP_FIELDS);
        types.put(WorkflowEventTypes.CHANNEL_MESSENGER_MESSAGE_RECEIVED, CHANNEL_FIELDS);
        types.put(WorkflowEventTypes.CHANNEL_INSTAGRAM_MESSAGE_RECEIVED, CHANNEL_FIELDS);
        types.put(WorkflowEventTypes.CHANNEL_SLACK_MESSAGE_RECEIVED, CHANNEL_FIELDS);
        types.put(WorkflowEventTypes.CHANNEL_DISCORD_MESSAGE_RECEIVED, CHANNEL_FIELDS);
        types.put(WorkflowEventTypes.CHANNEL_X_MESSAGE_RECEIVED, CHANNEL_FIELDS);
        types.put(WorkflowEventTypes.CHANNEL_TELEGRAM_MESSAGE_RECEIVED, CHANNEL_FIELDS);
        types.put(WorkflowEventTypes.CHANNEL_EMAIL_MESSAGE_RECEIVED, CHANNEL_FIELDS);
        types.put(WorkflowEventTypes.CHANNEL_TEAMS_MESSAGE_RECEIVED, CHANNEL_FIELDS);
        types.put(WorkflowEventTypes.CHANNEL_SMS_MESSAGE_RECEIVED, CHANNEL_FIELDS);
        types.put(WorkflowEventTypes.DOCUMENT_GENERATION_COMPLETED, ordered("jobId", "tenantId", "success"));
        return Collections.unmodifiableMap(types);
    }

    /** Insertion-ordered, so a validation message lists a payload's fields the way its publisher writes them. */
    private static Set<String> ordered(String... fields) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(fields)));
    }

    public static boolean isSubscribable(String eventType) {
        return eventType != null && SUBSCRIBABLE.containsKey(eventType);
    }

    /** Sorted so the 400 message and the docs list them in a stable order. */
    public static Set<String> subscribable() {
        return new TreeSet<>(SUBSCRIBABLE.keySet());
    }

    /**
     * @return the payload fields this event type actually carries, or an empty
     *     set for an event type nobody may subscribe to. Never null, so a caller
     *     validating a template does not have to special-case the unknown type
     *     it has already rejected.
     */
    public static Set<String> payloadFieldsOf(String eventType) {
        return SUBSCRIBABLE.getOrDefault(eventType, Set.of());
    }
}
