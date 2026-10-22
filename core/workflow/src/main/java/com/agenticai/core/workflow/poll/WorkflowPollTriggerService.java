package com.agenticai.core.workflow.poll;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.security.net.SsrfBlockedException;
import com.agenticai.core.security.net.SsrfGuard;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.WorkflowValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Configures and executes HTTP polling triggers (V482). Two deliberately separate halves:
 *
 * <p><b>Configure</b> ({@link #configure}/{@link #disable}/{@link #get}) - called only from {@link
 * WorkflowService}'s poll wrappers, which have already run the workflow's
 * access/editable/unattended-automation gates and confirmed {@code nodeId} names a real Trigger
 * node in the current graph (the same split {@code WorkflowTriggerNodeService}'s javadoc documents:
 * validation here, authorization there). URL validation is {@link SsrfGuard#validate} at save time
 * AND the guarded client at poll time - save -time gives the configurer an immediate, legible
 * error; the guarded connect is what actually prevents SSRF (DNS can lie between the two).
 *
 * <p><b>Execute</b> ({@link #findDue}/{@link #runDue}) - called by {@link
 * WorkflowPollTriggerTicker} per tenant schema. {@link #runDue} is NOT {@code @Transactional}:
 * firing a workflow run makes outbound LLM calls, so holding a transaction across it is the same
 * mistake {@code WorkflowService#runScheduledWorkflow}'s javadoc warns about. Each state mutation
 * is its own {@code repository.save} - the seen-id ring is written AFTER the runs it suppresses,
 * which is the at-least-once direction (a crash between fire and save refires the item next poll;
 * the reverse order would drop it).
 *
 * <p>{@code WorkflowService} is injected lazily ({@link ObjectProvider}) for the same reason {@code
 * AgentScheduleService} injects {@code ScheduledWorkflowRunner} that way: the service is a
 * constructor dependency of {@code WorkflowService} (its poll endpoints delegate here), so a direct
 * constructor dependency back would be a Spring cycle.
 */
@Service
public class WorkflowPollTriggerService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowPollTriggerService.class);
    private static final String REQUESTED_BY_PREFIX = "system:workflow-poll:";
    private static final int MAX_PATH_LENGTH = 256;
    private static final int MAX_URL_LENGTH = 4096;
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST");
    private static final int MAX_HEADERS = 20;
    private static final int MAX_HEADER_VALUE_LENGTH = 512;

    private final WorkflowPollTriggerRepository repository;
    private final WorkflowPollFetcher fetcher;
    private final WorkflowPollProperties properties;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<WorkflowService> workflowService;
    private final SsrfGuard guard;

    // @Autowired marks the wiring ctor: the package-private test seam below is a
    // second ctor, and with two candidates Spring falls back to a (nonexistent)
    // default ctor instead of guessing - "No default constructor found" at boot.
    @org.springframework.beans.factory.annotation.Autowired
    public WorkflowPollTriggerService(
            WorkflowPollTriggerRepository repository,
            WorkflowPollFetcher fetcher,
            WorkflowPollProperties properties,
            EncryptionService encryptionService,
            ObjectMapper objectMapper,
            ObjectProvider<WorkflowService> workflowService) {
        this(
                repository,
                fetcher,
                properties,
                encryptionService,
                objectMapper,
                workflowService,
                SsrfGuard.defaultGuard());
    }

    /** Test seam: a guard over a stubbed resolver (no real DNS in unit tests). */
    WorkflowPollTriggerService(
            WorkflowPollTriggerRepository repository,
            WorkflowPollFetcher fetcher,
            WorkflowPollProperties properties,
            EncryptionService encryptionService,
            ObjectMapper objectMapper,
            ObjectProvider<WorkflowService> workflowService,
            SsrfGuard guard) {
        this.repository = repository;
        this.fetcher = fetcher;
        this.properties = properties;
        this.encryptionService = encryptionService;
        this.objectMapper = objectMapper;
        this.workflowService = workflowService;
        this.guard = guard;
    }

    /** Validated configure request, already checked by the caller's access gates. */
    public record ConfigureRequest(
            String url,
            String method,
            Map<String, String> headers,
            Integer intervalSeconds,
            String itemsPath,
            String idPath,
            Boolean seedOnEnable) {}

    /**
     * Creates or replaces the trigger node's poll binding. Reconfiguring resets the seen-id state
     * (see {@link WorkflowPollTrigger#configure}). Returns the saved row.
     */
    @Transactional
    public WorkflowPollTrigger configure(UUID workflowId, String nodeId, ConfigureRequest request) {
        String url = validateUrl(request.url());
        String method =
                request.method() == null || request.method().isBlank()
                        ? "GET"
                        : request.method().trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            throw new WorkflowValidationException(
                    List.of("Poll trigger method must be GET or POST."));
        }
        int interval = request.intervalSeconds() == null ? 300 : request.intervalSeconds();
        if (interval < properties.getMinIntervalSeconds()
                || interval > properties.getMaxIntervalSeconds()) {
            throw new WorkflowValidationException(
                    List.of(
                            "Poll interval must be between "
                                    + properties.getMinIntervalSeconds()
                                    + " and "
                                    + properties.getMaxIntervalSeconds()
                                    + " seconds."));
        }
        String itemsPath = boundedPath(request.itemsPath(), "itemsPath");
        String idPath = boundedPath(request.idPath() == null ? "id" : request.idPath(), "idPath");
        String headersEncrypted = encryptHeaders(request.headers());
        boolean seed = request.seedOnEnable() == null || request.seedOnEnable();

        WorkflowPollTrigger trigger =
                repository
                        .findByNodeId(nodeId)
                        .orElseGet(() -> new WorkflowPollTrigger(workflowId, nodeId));
        trigger.configure(url, method, headersEncrypted, interval, itemsPath, idPath, seed);
        return repository.save(trigger);
    }

    /** Full URL validation at save time (scheme, host rules, and a real DNS check). */
    private String validateUrl(String url) {
        if (url == null || url.isBlank() || url.length() > MAX_URL_LENGTH) {
            throw new WorkflowValidationException(List.of("Poll trigger needs an http(s) URL."));
        }
        try {
            guard.validate(url);
        } catch (SsrfBlockedException e) {
            throw new WorkflowValidationException(List.of("Poll URL refused: " + e.getMessage()));
        }
        return url.strip();
    }

    private String boundedPath(String path, String field) {
        if (path == null) {
            return "";
        }
        String trimmed = path.strip();
        if (trimmed.length() > MAX_PATH_LENGTH) {
            throw new WorkflowValidationException(
                    List.of(field + " is too long (max " + MAX_PATH_LENGTH + ")."));
        }
        return trimmed;
    }

    /**
     * Headers are stored AES-256-GCM-encrypted as a JSON object - they can carry an Authorization
     * header or API key, so they get the same at-rest treatment as the webhook token and are never
     * echoed back by the API (GET returns only {@code headersConfigured: true}).
     */
    private String encryptHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        if (headers.size() > MAX_HEADERS) {
            throw new WorkflowValidationException(
                    List.of("At most " + MAX_HEADERS + " poll headers."));
        }
        Map<String, String> clean = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String name = e.getKey() == null ? "" : e.getKey().strip();
            String value = e.getValue() == null ? "" : e.getValue().strip();
            if (name.isEmpty() || name.length() > 64 || value.length() > MAX_HEADER_VALUE_LENGTH) {
                throw new WorkflowValidationException(List.of("Invalid poll header: " + name));
            }
            clean.put(name, value);
        }
        try {
            return encryptionService.encrypt(objectMapper.writeValueAsString(clean));
        } catch (Exception e) {
            throw new WorkflowValidationException(List.of("Poll headers could not be stored."));
        }
    }

    @Transactional
    public void disable(String nodeId) {
        repository
                .findByNodeId(nodeId)
                .ifPresent(
                        t -> {
                            t.disable();
                            repository.save(t);
                        });
    }

    @Transactional
    public void delete(String nodeId) {
        repository.deleteByNodeId(nodeId);
    }

    @Transactional(readOnly = true)
    public Optional<WorkflowPollTrigger> get(String nodeId) {
        return repository.findByNodeId(nodeId);
    }

    /** The ticker's per-tick query - already tenant-scoped by the caller. */
    @Transactional(readOnly = true)
    public List<UUID> findDue(Instant now) {
        return repository.findByEnabledTrueAndNextPollAtLessThanEqual(now).stream()
                .map(WorkflowPollTrigger::getId)
                .toList();
    }

    /**
     * Executes one due poll: fetch, extract, dedupe on the seen-id ring, fire a run per new item
     * (capped), persist state. A run that fails to start does NOT consume the item id - it retries
     * on the next poll, which is the at-least-once semantic the whole trigger family uses.
     */
    public void runDue(UUID triggerId) {
        if (!properties.isEnabled()) {
            return;
        }
        WorkflowPollTrigger trigger = repository.findById(triggerId).orElse(null);
        if (trigger == null || !trigger.isEnabled()) {
            return;
        }
        PollState state = null;
        try {
            String body =
                    fetcher.fetch(trigger.getUrl(), trigger.getMethod(), decryptHeaders(trigger));
            List<PollItemExtractor.PolledItem> items =
                    PollItemExtractor.extract(
                            objectMapper, body, trigger.getItemsPath(), trigger.getIdPath());
            state = readState(trigger.getStateJson());
            if (!state.seeded() && trigger.isSeedOnEnable()) {
                // First poll after enabling: record what already exists, fire
                // nothing - "new items only from here" is what a poll trigger
                // means; firing the existing backlog would surprise everyone.
                state.seenIds()
                        .addAll(items.stream().map(PollItemExtractor.PolledItem::id).toList());
                trimRing(state.seenIds());
                trigger.markPolled(
                        WorkflowPollTrigger.STATUS_SEEDED,
                        writeState(state, true),
                        0,
                        properties.getFailureBackoffCap());
                repository.save(trigger);
                return;
            }
            fireNewItems(trigger, items, state);
            trimRing(state.seenIds());
            trigger.markPolled(
                    WorkflowPollTrigger.STATUS_OK,
                    writeState(state, true),
                    0,
                    properties.getFailureBackoffCap());
        } catch (RuntimeException e) {
            int failures = trigger.getConsecutiveFailures() + 1;
            log.warn(
                    "Poll trigger {} (workflow {}) failed ({} consecutive): {}",
                    trigger.getNodeId(),
                    trigger.getWorkflowId(),
                    failures,
                    e.getMessage());
            // State read before the failure still holds ids of items that DID
            // fire - persisting them keeps the at-least-once direction (a
            // dropped id would refire the item next poll).
            String keepState =
                    state == null ? trigger.getStateJson() : writeState(state, state.seeded());
            trigger.markPolled(
                    WorkflowPollTrigger.STATUS_ERROR,
                    keepState,
                    failures,
                    properties.getFailureBackoffCap());
        }
        repository.save(trigger);
    }

    private void fireNewItems(
            WorkflowPollTrigger trigger,
            List<PollItemExtractor.PolledItem> items,
            PollState state) {
        WorkflowService runner = workflowService.getIfAvailable();
        if (runner == null) {
            throw new PollFetchException("workflow runner unavailable");
        }
        int fired = 0;
        for (PollItemExtractor.PolledItem item : items) {
            if (state.seenIds().contains(item.id()) || fired >= properties.getMaxItemsPerPoll()) {
                continue;
            }
            String input = runInput(item.item());
            try {
                runner.run(
                        trigger.getWorkflowId(),
                        trigger.getNodeId(),
                        input,
                        REQUESTED_BY_PREFIX + trigger.getNodeId());
                state.seenIds().add(item.id());
                fired++;
            } catch (RuntimeException e) {
                // The run could not even start - leave the id unseen so it
                // retries next poll; the rest of this batch still fires.
                log.warn(
                        "Poll trigger {} could not start a run for item {}: {}",
                        trigger.getNodeId(),
                        item.id(),
                        e.getMessage());
            }
        }
        if (fired > 0) {
            log.info(
                    "Poll trigger {} (workflow {}) fired {} new item(s)",
                    trigger.getNodeId(),
                    trigger.getWorkflowId(),
                    fired);
        }
    }

    private String runInput(JsonNode item) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("item", item);
        payload.put("trigger", "poll");
        payload.put("polledAt", Instant.now().toString());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return item.toString();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> decryptHeaders(WorkflowPollTrigger trigger) {
        if (trigger.getHeadersEncrypted() == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(
                    encryptionService.decrypt(trigger.getHeadersEncrypted()), Map.class);
        } catch (Exception e) {
            throw new PollFetchException("stored poll headers could not be decrypted", e);
        }
    }

    private PollState readState(String stateJson) {
        PollState state = new PollState(new java.util.LinkedHashSet<>(), false);
        if (stateJson == null || stateJson.isBlank()) {
            return state;
        }
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(stateJson);
            if (node.get("seenIds") instanceof ArrayNode ids) {
                ids.forEach(id -> state.seenIds().add(id.asText()));
            }
            return new PollState(state.seenIds(), node.path("seeded").asBoolean(false));
        } catch (Exception e) {
            // Corrupt state is recovered empty rather than failing the poll:
            // worst case is re-firing items the ring had already suppressed.
            return state;
        }
    }

    private String writeState(PollState state, boolean seeded) {
        ObjectNode node = objectMapper.createObjectNode();
        ArrayNode ids = node.putArray("seenIds");
        state.seenIds().forEach(ids::add);
        node.put("seeded", seeded);
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"seeded\":true,\"seenIds\":[]}";
        }
    }

    /** Drops the oldest ids once the ring exceeds the configured cap. */
    private void trimRing(java.util.LinkedHashSet<String> seenIds) {
        while (seenIds.size() > properties.getSeenIdsCap()) {
            seenIds.remove(seenIds.iterator().next());
        }
    }

    /** Order-preserving so the ring's eviction order is insertion order. */
    private record PollState(java.util.LinkedHashSet<String> seenIds, boolean seeded) {}
}
