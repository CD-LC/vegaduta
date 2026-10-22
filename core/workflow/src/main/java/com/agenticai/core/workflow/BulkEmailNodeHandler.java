package com.agenticai.core.workflow;

import static com.agenticai.core.workflow.WorkflowNodeSupport.stringConfig;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.ChannelException;
import com.agenticai.core.omnichannel.contact.ChannelContact;
import com.agenticai.core.omnichannel.contact.ChannelContactRepository;
import com.agenticai.core.omnichannel.outbound.BulkEmailLedger;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.output.BulkEmailSender;
import com.agenticai.core.workflow.output.WorkflowOutputDedupGuard;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * "Bulk Email" node ({@link WorkflowNodeType#BULK_EMAIL}). One node, many recipients, each getting
 * its own personalized message.
 *
 * <p><b>Recipients</b> ({@code recipientsSource}): {@code items} - a JSON array taken from this
 * node's input ({@code itemsField} blank = input is the array; same contract as Loop/Fan-out), each
 * element either an address string or an object whose {@code emailField} (default {@code email}) is
 * the address and whose other fields become {@code {{vars}}}; {@code group} - the EMAIL contacts of
 * a {@code contact_group} ({@code groupId}); {@code static} - a comma/newline separated list.
 *
 * <p><b>Bounded by design.</b> A node execution runs on a workflow run thread (or a Temporal
 * activity thread), never the shared scheduler pool, but it must not park there for minutes either:
 * {@code maxRecipients} (default 100, ceiling 500) caps the batch, {@code maxPerMinute} (default
 * 60, ceiling 600) sets the inter-send delay, and total sleeping is capped at {@link
 * #THROTTLE_BUDGET_MS}. Recipients that would need more sleep than the budget allows are reported
 * as {@code deferred} (never silently dropped, never sent faster than the configured rate).
 *
 * <p><b>Per recipient</b>, in order: syntactic check, in-batch duplicate, suppression list ({@code
 * email_suppression}), {@code skipIfReplied}, dedup claim ({@link WorkflowOutputDedupGuard}, keyed
 * by run, node and a hash of the address so an at-least-once retry cannot double-send),
 * personalization, send, persist to {@code email_message}. Output is a JSON object: {@code
 * sent/skipped/failed/deferred/truncated} counts plus one {@code rows[]} entry per recipient.
 */
@Component
public class BulkEmailNodeHandler {

    private static final Logger log = LoggerFactory.getLogger(BulkEmailNodeHandler.class);

    static final int DEFAULT_MAX_RECIPIENTS = 100;
    static final int MAX_RECIPIENTS_CEILING = 500;
    static final int DEFAULT_PER_MINUTE = 60;
    static final int MAX_PER_MINUTE_CEILING = 600;

    /** Total sleep one node execution may spend throttling. */
    static final long THROTTLE_BUDGET_MS = 90_000L;

    private static final Pattern EMAIL =
            Pattern.compile("^[^@\\s<>,;\"]+@[^@\\s<>,;\"]+\\.[^@\\s<>,;\"]+$");

    /** Test seam so the throttle is verifiable without real sleeping. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private record Recipient(String email, Map<String, String> vars) {}

    private final BulkEmailSender sender;
    // The email channel's suppression list, reply history, send log and
    // unsubscribe links (ADR-0011 rule a: the channel owns them).
    private final BulkEmailLedger ledger;
    private final ChannelContactRepository contactRepository;
    private final WorkflowOutputDedupGuard dedupGuard;
    private final ObjectMapper objectMapper;
    private final Sleeper sleeper;
    private final WorkflowExpressionResolver defaultRenderer;

    @Autowired
    public BulkEmailNodeHandler(
            BulkEmailSender sender,
            BulkEmailLedger ledger,
            ChannelContactRepository contactRepository,
            WorkflowOutputDedupGuard dedupGuard,
            ObjectMapper objectMapper) {
        this(sender, ledger, contactRepository, dedupGuard, objectMapper, Thread::sleep);
    }

    BulkEmailNodeHandler(
            BulkEmailSender sender,
            BulkEmailLedger ledger,
            ChannelContactRepository contactRepository,
            WorkflowOutputDedupGuard dedupGuard,
            ObjectMapper objectMapper,
            Sleeper sleeper) {
        this.sender = sender;
        this.ledger = ledger;
        this.contactRepository = contactRepository;
        this.dedupGuard = dedupGuard;
        this.objectMapper = objectMapper;
        this.sleeper = sleeper;
        this.defaultRenderer = WorkflowExpressionResolver.standalone(objectMapper);
    }

    /**
     * Standalone entry (direct callers and tests): subject/body are rendered per recipient by a
     * run-less {@link WorkflowExpressionResolver}. {@link WorkflowNodeExecutor} passes the run's
     * own renderer instead, which also resolves {@code $node} / {@code $trigger}.
     */
    public NodeExecutionResult execute(String runId, WorkflowNodeDto node, String input) {
        return execute(runId, node, input, defaultRenderer.recipientRenderer(input, null, null));
    }

    /**
     * @param renderer renders a subject/body template for one recipient: bare {@code {{name}}} is
     *     that recipient's field, {@code $item} the recipient, everything else resolves against the
     *     run.
     */
    public NodeExecutionResult execute(
            String runId,
            WorkflowNodeDto node,
            String input,
            WorkflowExpressionResolver.RecipientRenderer renderer) {
        List<String> logLines = new ArrayList<>();
        try {
            sender.requireConfigured();
        } catch (ChannelConfigurationException e) {
            logLines.add("Bulk email unavailable: " + e.getMessage());
            return NodeExecutionResult.skipped(input, e.getMessage(), logLines);
        }

        String subjectTemplate = stringConfig(node, "subject", "");
        String bodyTemplate = stringConfig(node, "body", "");
        if (subjectTemplate.isBlank() || bodyTemplate.isBlank()) {
            return NodeExecutionResult.skipped(
                    input, "Bulk Email needs both a subject and a body.", logLines);
        }
        boolean html = boolConfig(node, "html", false);
        boolean skipIfReplied = boolConfig(node, "skipIfReplied", false);
        boolean includeUnsubscribe = boolConfig(node, "includeUnsubscribe", true);
        String replyTo = stringConfig(node, "replyTo", null);
        if (replyTo != null && !EMAIL.matcher(replyTo.trim()).matches()) {
            logLines.add("Ignoring invalid replyTo address.");
            replyTo = null;
        }
        int maxRecipients =
                clamp(
                        intConfig(node, "maxRecipients", DEFAULT_MAX_RECIPIENTS),
                        1,
                        MAX_RECIPIENTS_CEILING);
        int perMinute =
                clamp(
                        intConfig(node, "maxPerMinute", DEFAULT_PER_MINUTE),
                        1,
                        MAX_PER_MINUTE_CEILING);
        long delayMs = 60_000L / perMinute;

        List<Recipient> resolved;
        try {
            resolved = resolveRecipients(node, input, logLines);
        } catch (IllegalArgumentException e) {
            return NodeExecutionResult.skipped(input, e.getMessage(), logLines);
        }
        if (resolved.isEmpty()) {
            return NodeExecutionResult.skipped(
                    input, "Bulk Email resolved no recipients.", logLines);
        }
        int truncated = Math.max(0, resolved.size() - maxRecipients);
        List<Recipient> recipients = truncated > 0 ? resolved.subList(0, maxRecipients) : resolved;
        if (truncated > 0) {
            logLines.add(
                    "Recipient list capped at "
                            + maxRecipients
                            + " - "
                            + truncated
                            + " not processed.");
        }

        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        boolean canUnsubscribe = tenantId != null && ledger.canUnsubscribe();
        if (!canUnsubscribe && includeUnsubscribe) {
            logLines.add(
                    "No unsubscribe link added - set OMNICHANNEL_PUBLIC_BASE_URL (and an encryption/unsubscribe secret).");
        }

        Set<String> suppressed;
        try {
            suppressed = loadSuppressed(recipients);
        } catch (ChannelException e) {
            logLines.add(e.getMessage());
            return NodeExecutionResult.skipped(input, e.getMessage(), logLines);
        }
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        int sent = 0;
        int skipped = 0;
        int failed = 0;
        int deferred = 0;
        long sleptMs = 0;
        boolean stopThrottled = false;
        boolean attemptedSend = false;
        int sentIndex = 0;

        for (Recipient recipient : recipients) {
            String email = recipient.email();
            String lower = email.toLowerCase(Locale.ROOT);
            if (stopThrottled) {
                deferred++;
                rows.add(row(email, "deferred", "reason", "throttle budget exhausted"));
                continue;
            }
            if (!EMAIL.matcher(email).matches()) {
                skipped++;
                rows.add(row(email, "skipped", "reason", "invalid address"));
                continue;
            }
            if (!seen.add(lower)) {
                skipped++;
                rows.add(row(email, "skipped", "reason", "duplicate in list"));
                continue;
            }
            if (suppressed.contains(lower)) {
                skipped++;
                rows.add(row(email, "skipped", "reason", "suppressed"));
                continue;
            }
            if (skipIfReplied && repliedBefore(lower)) {
                skipped++;
                rows.add(row(email, "skipped", "reason", "already replied"));
                continue;
            }
            String dedupKey =
                    dedupGuard.identityKey("bulk_email:" + shortHash(lower), runId, node.id());
            if (!dedupGuard.tryClaim(dedupKey)) {
                skipped++;
                rows.add(
                        row(
                                email,
                                "skipped",
                                "reason",
                                "duplicate send (already sent for this run)"));
                continue;
            }

            if (attemptedSend) {
                if (sleptMs + delayMs > THROTTLE_BUDGET_MS) {
                    dedupGuard.release(dedupKey);
                    stopThrottled = true;
                    deferred++;
                    rows.add(row(email, "deferred", "reason", "throttle budget exhausted"));
                    continue;
                }
                try {
                    sleeper.sleep(delayMs);
                    sleptMs += delayMs;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    dedupGuard.release(dedupKey);
                    stopThrottled = true;
                    deferred++;
                    rows.add(row(email, "deferred", "reason", "interrupted"));
                    continue;
                }
            }
            attemptedSend = true;

            String unsubscribeUrl = canUnsubscribe ? ledger.unsubscribeUrl(tenantId, email) : null;
            Map<String, String> vars = new HashMap<>(recipient.vars());
            vars.put("email", email);
            vars.putIfAbsent("name", localPart(email));
            vars.put("unsubscribe_url", unsubscribeUrl == null ? "" : unsubscribeUrl);
            int recipientIndex = sentIndex++;
            String subject;
            String body;
            try {
                subject =
                        oneLine(renderer.render("subject", subjectTemplate, vars, recipientIndex));
                body =
                        html
                                ? renderer.renderHtml("body", bodyTemplate, vars, recipientIndex)
                                : renderer.render("body", bodyTemplate, vars, recipientIndex);
            } catch (RuntimeException e) {
                // A template that cannot render for this recipient must not abort the campaign
                // mid-loop, after
                // other recipients were already mailed: release the claim so a retry can still
                // reach them.
                dedupGuard.release(dedupKey);
                failed++;
                rows.add(row(email, "failed", "error", "template error: " + e.getMessage()));
                log.warn("Bulk email template failed for one recipient: {}", e.getMessage());
                continue;
            }
            if (includeUnsubscribe
                    && unsubscribeUrl != null
                    && !bodyTemplate.contains("{{unsubscribe_url}}")) {
                body =
                        html
                                ? body
                                        + "<p style=\"font-size:12px;color:#666\"><a href=\""
                                        + HtmlUtils.htmlEscape(unsubscribeUrl)
                                        + "\">Unsubscribe</a></p>"
                                : body + "\n\n--\nUnsubscribe: " + unsubscribeUrl;
            }

            String threadKey = shortHash(runId + "|" + node.id() + "|" + lower);
            try {
                BulkEmailSender.Sent result =
                        sender.send(
                                email,
                                subject,
                                body,
                                html,
                                replyTo,
                                includeUnsubscribe ? unsubscribeUrl : null);
                sent++;
                rows.add(row(email, "sent", "messageId", result.messageId()));
                persist(
                        () ->
                                ledger.recordSent(
                                        runId,
                                        node.id(),
                                        email,
                                        subject,
                                        result.messageId(),
                                        threadKey,
                                        Instant.now()));
            } catch (RuntimeException e) {
                dedupGuard.release(dedupKey);
                failed++;
                String reason = e instanceof ChannelException ? e.getMessage() : "send failed";
                rows.add(row(email, "failed", "error", reason));
                persist(
                        () ->
                                ledger.recordFailed(
                                        runId, node.id(), email, subject, threadKey, reason));
                log.warn("Bulk email send to one recipient failed: {}", e.getMessage());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sent", sent);
        out.put("skipped", skipped);
        out.put("failed", failed);
        out.put("deferred", deferred);
        out.put("truncated", truncated);
        out.put("total", recipients.size());
        out.put("rows", rows);
        String json;
        try {
            json = objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            json = "{\"sent\":" + sent + ",\"skipped\":" + skipped + ",\"failed\":" + failed + "}";
        }
        String summary =
                "Bulk email: "
                        + sent
                        + " sent, "
                        + skipped
                        + " skipped, "
                        + failed
                        + " failed"
                        + (deferred > 0 ? ", " + deferred + " deferred" : "")
                        + ".";
        logLines.add(summary);
        if (sent == 0 && failed > 0) {
            return NodeExecutionResult.skipped(json, summary, logLines);
        }
        return NodeExecutionResult.ok(json, summary, null, logLines);
    }

    // ---- recipients ----------------------------------------------------

    private List<Recipient> resolveRecipients(
            WorkflowNodeDto node, String input, List<String> logLines) {
        String source = stringConfig(node, "recipientsSource", "items");
        return switch (source) {
            case "group" -> fromGroup(node, logLines);
            case "static" -> fromStatic(node);
            default -> fromItems(node, input, logLines);
        };
    }

    private List<Recipient> fromItems(WorkflowNodeDto node, String input, List<String> logLines) {
        String itemsField = stringConfig(node, "itemsField", "").trim();
        String emailField = stringConfig(node, "emailField", "email").trim();
        JsonNode root;
        try {
            root = objectMapper.readTree(input == null || input.isBlank() ? "[]" : input);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Bulk Email input is not valid JSON - expected an array of recipients.");
        }
        JsonNode array = itemsField.isEmpty() ? root : root.path(itemsField);
        if (!array.isArray()) {
            throw new IllegalArgumentException(
                    "Bulk Email found no recipients array"
                            + (itemsField.isEmpty()
                                    ? " in its input."
                                    : " at '" + itemsField + "'."));
        }
        List<Recipient> out = new ArrayList<>();
        for (JsonNode item : array) {
            if (item.isString()) {
                out.add(new Recipient(item.asString().trim(), Map.of()));
            } else if (item.isObject()) {
                Map<String, String> vars = new HashMap<>();
                com.agenticai.core.json.JsonNodes.fields(item)
                        .forEachRemaining(
                                e ->
                                        vars.put(
                                                e.getKey(),
                                                e.getValue().isString()
                                                        ? e.getValue().asString()
                                                        : e.getValue().toString()));
                String email = vars.get(emailField);
                if (email == null || email.isBlank()) {
                    logLines.add("Skipped an item with no '" + emailField + "' field.");
                    continue;
                }
                out.add(new Recipient(email.trim(), vars));
            }
        }
        return out;
    }

    private List<Recipient> fromGroup(WorkflowNodeDto node, List<String> logLines) {
        String groupId = stringConfig(node, "groupId", null);
        UUID id;
        try {
            id = UUID.fromString(groupId == null ? "" : groupId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Bulk Email needs a valid contact group.");
        }
        List<Recipient> out = new ArrayList<>();
        for (ChannelContact contact : contactRepository.findByGroupIdOrderByCreatedAtDesc(id)) {
            if (contact.getChannel() != ChannelType.EMAIL) {
                continue;
            }
            Map<String, String> vars = new HashMap<>();
            if (contact.getDisplayName() != null && !contact.getDisplayName().isBlank()) {
                vars.put("name", contact.getDisplayName());
            }
            out.add(new Recipient(contact.getIdentifier().trim(), vars));
        }
        logLines.add("Resolved " + out.size() + " email contact(s) from the group.");
        return out;
    }

    private List<Recipient> fromStatic(WorkflowNodeDto node) {
        Object raw = node.config().get("staticRecipients");
        List<String> tokens = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    tokens.add(o.toString());
                }
            }
        } else if (raw != null) {
            for (String part : raw.toString().split("[,;\\s]+")) {
                tokens.add(part);
            }
        }
        List<Recipient> out = new ArrayList<>();
        for (String t : tokens) {
            if (!t.isBlank()) {
                out.add(new Recipient(t.trim(), Map.of()));
            }
        }
        return out;
    }

    // ---- helpers -------------------------------------------------------

    private Set<String> loadSuppressed(List<Recipient> recipients) {
        List<String> lowered =
                recipients.stream()
                        .map(r -> r.email().toLowerCase(Locale.ROOT))
                        .distinct()
                        .toList();
        Set<String> out;
        try {
            out = new HashSet<>(ledger.suppressed(lowered));
        } catch (RuntimeException e) {
            // Fail CLOSED: a suppression list we cannot read must not be treated as empty.
            throw new ChannelConfigurationException(
                    "email", "Could not read the suppression list: " + e.getMessage());
        }
        return out;
    }

    private boolean repliedBefore(String lower) {
        try {
            return ledger.hasHumanReplyFrom(lower);
        } catch (RuntimeException e) {
            log.warn("skipIfReplied lookup failed - treating as not replied", e);
            return false;
        }
    }

    private void persist(Runnable record) {
        try {
            record.run();
        } catch (RuntimeException e) {
            log.warn("Could not persist email_message row - the send itself is unaffected", e);
        }
    }

    private static Map<String, Object> row(String email, String status, String key, String value) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("recipient", email);
        r.put("status", status);
        r.put(key, value);
        return r;
    }

    /** CR/LF in a header value is header injection; collapse to a space. */
    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static String localPart(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    private static String shortHash(String value) {
        try {
            byte[] d =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean boolConfig(WorkflowNodeDto node, String key, boolean fallback) {
        Object raw = node.config().get(key);
        if (raw instanceof Boolean b) {
            return b;
        }
        return raw == null || raw.toString().isBlank()
                ? fallback
                : Boolean.parseBoolean(raw.toString());
    }

    private static int intConfig(WorkflowNodeDto node, String key, int fallback) {
        Object raw = node.config().get(key);
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return raw == null || raw.toString().isBlank()
                    ? fallback
                    : Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(v, hi));
    }
}
