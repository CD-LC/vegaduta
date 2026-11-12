package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemorySourceRef;
import com.agenticai.core.security.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The human side of the store: approve, reject, edit, revert and delete. {@link #approve} is the
 * ONLY code path that makes an item TRUSTED and ACTIVE, and it requires an identified human actor
 * (see {@link MemoryActor#isIdentifiedHuman}); it records {@code approved_by} and an APPROVED
 * event. Distillers, recorders, consolidation jobs and the proposal sink have no reference to this
 * class: {@code MemoryStoreArchTest} fails the build if a class outside the configuration, the REST
 * layer and this service depends on it, or if anything else calls {@link
 * MemoryItemStorage#activate}.
 *
 * <p>Every method returns an {@link Outcome}: {@code refusal} is a reason code (never memory text)
 * and nothing changed. Every successful change appends a {@link MemoryEvent} with ids, the actor
 * and the status change, in the same unit of work. These methods do not consult the feature flags:
 * a person must always be able to review, revert and delete, even with the platform switch off
 * (data is retained but not recalled).
 */
public class MemoryItemReviewService {

    private static final Logger log = LoggerFactory.getLogger(MemoryItemReviewService.class);

    /**
     * Result of a review operation: the affected item id (for an edit, the NEW version), or a
     * refusal code.
     */
    public record Outcome(UUID itemId, String refusal) {

        public boolean ok() {
            return refusal == null;
        }

        static Outcome done(UUID id) {
            return new Outcome(id, null);
        }

        static Outcome refused(String reason) {
            return new Outcome(null, reason);
        }
    }

    private final MemoryUnifiedProperties properties;
    private final MemoryItemStorage storage;
    private final ObjectProvider<MemoryEmbedder> embedder;
    private final List<MemoryContentScreen> screens;
    private final Clock clock;

    public MemoryItemReviewService(
            MemoryUnifiedProperties properties,
            MemoryItemStorage storage,
            ObjectProvider<MemoryEmbedder> embedder,
            List<MemoryContentScreen> screens,
            Clock clock) {
        this.properties = properties;
        this.storage = storage;
        this.embedder = embedder;
        this.screens = List.copyOf(screens);
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reads for the review queue

    public Optional<MemoryItem> get(UUID agentId, UUID itemId) {
        return hasTenant() ? storage.find(agentId, itemId) : Optional.empty();
    }

    public List<MemoryItem> pending(UUID agentId, int limit) {
        return hasTenant()
                ? storage.listByStatus(agentId, MemoryItemStatus.PENDING, limit)
                : List.of();
    }

    /**
     * An agent's items for the REST list endpoint; {@code status} null = every status, {@code kind}
     * null = every kind. No trust filtering happens here: the controller decides who may see which
     * status (a VIEWER only ever asks for ACTIVE, which is TRUSTED by the CHECK constraint).
     */
    public List<MemoryItem> list(
            UUID agentId, MemoryItemStatus status, MemoryKind kind, int limit) {
        return hasTenant() ? storage.listByAgent(agentId, status, kind, limit) : List.of();
    }

    /** Every item about {@code subjectKey} across the tenant's agents (the per-user export). */
    public List<MemoryItem> forSubject(String subjectKey, int limit) {
        if (!hasTenant() || subjectKey == null || subjectKey.isBlank()) {
            return List.of();
        }
        return storage.listForSubject(subjectKey, limit);
    }

    /** Lineage of an item that belongs to the agent; empty when the item is not the agent's. */
    public List<MemoryItemSource> lineage(UUID agentId, UUID itemId) {
        return storage.find(agentId, itemId).isPresent() ? storage.lineage(itemId) : List.of();
    }

    /**
     * Event history of an item that belongs to the agent; empty when the item is not the agent's.
     */
    public List<MemoryEvent> events(UUID agentId, UUID itemId) {
        return storage.find(agentId, itemId).isPresent() ? storage.events(itemId) : List.of();
    }

    // ------------------------------------------------------------------ decisions

    /**
     * PENDING to TRUSTED + ACTIVE. Refused unless the actor is an identified human, the item is
     * PENDING, unexpired and within the generation cap, and its text still passes the screens.
     * Approving a derived item approves neither its sources nor anything derived from it. When the
     * item is an edit of an ACTIVE version, that predecessor becomes SUPERSEDED in the same unit of
     * work.
     */
    public Outcome approve(UUID agentId, UUID itemId, MemoryActor actor) {
        return guarded(
                agentId,
                itemId,
                actor,
                "APPROVE",
                item -> {
                    Optional<String> refusal =
                            MemoryTrustPolicy.activationRefusal(
                                    item, properties.getMaxGeneration(), clock.instant());
                    if (refusal.isPresent()) {
                        return Outcome.refused(refusal.get());
                    }
                    Optional<String> screened = screen(item.content());
                    if (screened.isPresent()) {
                        return Outcome.refused(screened.get());
                    }
                    Instant now = clock.instant();
                    MemoryEvent approved =
                            MemoryEvent.of(
                                    itemId,
                                    agentId,
                                    MemoryEvent.Type.APPROVED,
                                    actor,
                                    MemoryItemStatus.PENDING,
                                    MemoryItemStatus.ACTIVE,
                                    "KIND="
                                            + item.kind()
                                            + ";TAINTED="
                                            + !item.sourceTaint().isEmpty()
                                            + ";GENERATION="
                                            + item.generation(),
                                    now);
                    UUID predecessor = item.supersedesId();
                    MemoryEvent superseded =
                            predecessor == null
                                    ? null
                                    : MemoryEvent.of(
                                            predecessor,
                                            agentId,
                                            MemoryEvent.Type.EDITED,
                                            actor,
                                            MemoryItemStatus.ACTIVE,
                                            MemoryItemStatus.SUPERSEDED,
                                            "SUPERSEDED_BY_VERSION=" + item.version(),
                                            now);
                    if (!storage.activate(
                            agentId, itemId, actor.ref(), now, approved, predecessor, superseded)) {
                        return Outcome.refused("NOT_PENDING");
                    }
                    supersedeConsolidatedSources(item, actor, now);
                    return Outcome.done(itemId);
                });
    }

    /**
     * A merge or summary the nightly consolidation proposed replaces what it was built from, and
     * only a person's approval may do that: once this item is ACTIVE, every ACTIVE {@code
     * memory_item} in its lineage goes to SUPERSEDED (an EDITED event each, by the approving
     * human). Lineage stays, so erasing a source still reaches this item. Other derived items (a
     * distilled procedure, a self-assessment) never supersede their sources. The sweep has no way
     * to get here: it holds neither this service nor the storage port. A failure to supersede one
     * source leaves it ACTIVE next to the new item (a duplicate, never a loss), is logged, and the
     * approval stands.
     */
    private void supersedeConsolidatedSources(MemoryItem item, MemoryActor actor, Instant now) {
        boolean merge = MemoryItem.SOURCE_CONSOLIDATION_MERGE.equals(item.sourceType());
        boolean summary = MemoryItem.SOURCE_CONSOLIDATION_SUMMARY.equals(item.sourceType());
        if ((!merge && !summary) || item.writer() != MemoryWriter.DISTILLER) {
            return;
        }
        for (MemoryItemSource source : storage.lineage(item.id())) {
            if (!MemorySourceRef.MEMORY_ITEM.equals(source.sourceTable())) {
                continue;
            }
            try {
                UUID sourceId = UUID.fromString(source.sourceKey());
                if (!sourceId.equals(item.id())) {
                    storage.transition(
                            item.agentId(),
                            sourceId,
                            Set.of(MemoryItemStatus.ACTIVE),
                            MemoryItemStatus.SUPERSEDED,
                            MemoryEvent.of(
                                    sourceId,
                                    item.agentId(),
                                    MemoryEvent.Type.EDITED,
                                    actor,
                                    MemoryItemStatus.ACTIVE,
                                    MemoryItemStatus.SUPERSEDED,
                                    "SUPERSEDED_BY_CONSOLIDATION=" + (merge ? "MERGE" : "SUMMARY"),
                                    now));
                }
            } catch (RuntimeException e) {
                log.warn("Could not supersede one source of an approved consolidation proposal", e);
            }
        }
    }

    /** PENDING to REJECTED. */
    public Outcome reject(UUID agentId, UUID itemId, MemoryActor actor) {
        return guarded(
                agentId,
                itemId,
                actor,
                "REJECT",
                item ->
                        move(
                                item,
                                actor,
                                Set.of(MemoryItemStatus.PENDING),
                                MemoryItemStatus.REJECTED,
                                MemoryEvent.Type.REJECTED));
    }

    /** ACTIVE to REVERTED: recall stops returning it at once (the recall query checks status). */
    public Outcome revert(UUID agentId, UUID itemId, MemoryActor actor) {
        return guarded(
                agentId,
                itemId,
                actor,
                "REVERT",
                item ->
                        move(
                                item,
                                actor,
                                Set.of(MemoryItemStatus.ACTIVE),
                                MemoryItemStatus.REVERTED,
                                MemoryEvent.Type.REVERTED));
    }

    /**
     * A human edit is a new version, never an in-place change: a new PENDING row (version + 1,
     * {@code supersedes_id} set, writer HUMAN, re-screened and re-embedded) that keeps the old
     * taint and generation and records the old row in its lineage. A PENDING predecessor is
     * superseded at once; an ACTIVE one keeps being recalled until the new version is approved. The
     * new version needs its own approval. Returns the new version's id.
     */
    public Outcome edit(UUID agentId, UUID itemId, String newText, MemoryActor actor) {
        return guarded(
                agentId,
                itemId,
                actor,
                "EDIT",
                item -> {
                    if (item.status() != MemoryItemStatus.PENDING
                            && item.status() != MemoryItemStatus.ACTIVE) {
                        return Outcome.refused("NOT_EDITABLE");
                    }
                    Optional<String> screened = screen(newText);
                    if (screened.isPresent()) {
                        return Outcome.refused(screened.get());
                    }
                    MemoryEmbedder embed = embedder.getIfAvailable();
                    if (embed == null) {
                        return Outcome.refused("NO_EMBEDDER");
                    }
                    Instant now = clock.instant();
                    UUID id = UUID.randomUUID();
                    MemoryItem next =
                            MemoryItem.pending(
                                    id,
                                    agentId,
                                    item.kind(),
                                    newText,
                                    MemoryWriter.HUMAN,
                                    "HUMAN_UI",
                                    null,
                                    null,
                                    item.sourceTaint(),
                                    false,
                                    item.generation(),
                                    item.subjectKeys(),
                                    item.version() + 1,
                                    item.id(),
                                    now,
                                    item.expiresAt());
                    MemoryEvent created =
                            MemoryEvent.of(
                                    id,
                                    agentId,
                                    MemoryEvent.Type.CREATED,
                                    actor,
                                    null,
                                    MemoryItemStatus.PENDING,
                                    "KIND="
                                            + item.kind()
                                            + ";VERSION="
                                            + next.version()
                                            + ";EDIT_OF_STATUS="
                                            + item.status(),
                                    now);
                    storage.insertPending(
                            next,
                            embed.embed(newText),
                            List.of(
                                    new MemoryItemSource(
                                            MemorySourceRef.MEMORY_ITEM,
                                            item.id().toString(),
                                            item.trust())),
                            created);
                    if (item.status() == MemoryItemStatus.PENDING) {
                        storage.transition(
                                agentId,
                                item.id(),
                                Set.of(MemoryItemStatus.PENDING),
                                MemoryItemStatus.SUPERSEDED,
                                MemoryEvent.of(
                                        item.id(),
                                        agentId,
                                        MemoryEvent.Type.EDITED,
                                        actor,
                                        MemoryItemStatus.PENDING,
                                        MemoryItemStatus.SUPERSEDED,
                                        "SUPERSEDED_BY_VERSION=" + next.version(),
                                        now));
                    }
                    return Outcome.done(id);
                });
    }

    /**
     * Pins an ACTIVE item: consolidation and eviction never touch a pinned row (the planner treats
     * {@code pinned} as "never an input of any operation"). Only an identified human can pin, and
     * the flag is not part of any proposal, so an agent, distiller or job can never pin (or unpin)
     * anything. Pinning an already pinned item is a no-op success. Appends a PINNED event (ids and
     * the actor, no text).
     */
    public Outcome pin(UUID agentId, UUID itemId, MemoryActor actor) {
        return guarded(
                agentId,
                itemId,
                actor,
                "PIN",
                item -> {
                    if (item.status() != MemoryItemStatus.ACTIVE) {
                        return Outcome.refused("NOT_ACTIVE");
                    }
                    return setPinned(item, true, actor, MemoryEvent.Type.PINNED);
                });
    }

    /**
     * Clears the pin of an item in any status (a person can always release a row to normal
     * handling).
     */
    public Outcome unpin(UUID agentId, UUID itemId, MemoryActor actor) {
        return guarded(
                agentId,
                itemId,
                actor,
                "UNPIN",
                item -> setPinned(item, false, actor, MemoryEvent.Type.UNPINNED));
    }

    private Outcome setPinned(
            MemoryItem item, boolean pinned, MemoryActor actor, MemoryEvent.Type type) {
        if (item.pinned() == pinned) {
            return Outcome.done(item.id());
        }
        MemoryEvent event =
                MemoryEvent.of(
                        item.id(),
                        item.agentId(),
                        type,
                        actor,
                        item.status(),
                        item.status(),
                        "KIND=" + item.kind() + ";PINNED=" + pinned,
                        clock.instant());
        return storage.setPinned(item.agentId(), item.id(), pinned, event)
                ? Outcome.done(item.id())
                : Outcome.refused("CONFLICT");
    }

    /** Hard delete (erasure of one item) with an ERASED event; the event outlives the row. */
    public Outcome delete(UUID agentId, UUID itemId, MemoryActor actor) {
        return guarded(
                agentId,
                itemId,
                actor,
                "DELETE",
                item -> {
                    MemoryEvent erased =
                            MemoryEvent.of(
                                    itemId,
                                    agentId,
                                    MemoryEvent.Type.ERASED,
                                    actor,
                                    item.status(),
                                    null,
                                    "KIND=" + item.kind(),
                                    clock.instant());
                    return storage.delete(agentId, itemId, erased)
                            ? Outcome.done(itemId)
                            : Outcome.refused("NOT_FOUND");
                });
    }

    /**
     * Erasure cascade (WP6): removes the item and everything derived from it, each erased row's
     * lineage and its event history, leaving one ERASED tombstone per erased id. Unlike {@link
     * #delete}, a derived summary cannot keep erased content alive. Erasure works with the feature
     * flags off (ADR-0028 rollout: data is retained but erasable).
     */
    public Outcome erase(UUID agentId, UUID itemId, MemoryActor actor) {
        return guarded(
                agentId,
                itemId,
                actor,
                "ERASE",
                item -> {
                    List<UUID> erased = storage.eraseTree(agentId, itemId, actor, clock.instant());
                    return erased.isEmpty() ? Outcome.refused("NOT_FOUND") : Outcome.done(itemId);
                });
    }

    // ------------------------------------------------------------------ helpers

    private Outcome move(
            MemoryItem item,
            MemoryActor actor,
            Set<MemoryItemStatus> from,
            MemoryItemStatus to,
            MemoryEvent.Type type) {
        if (!from.contains(item.status())) {
            return Outcome.refused("NOT_" + from.iterator().next());
        }
        MemoryEvent event =
                MemoryEvent.of(
                        item.id(),
                        item.agentId(),
                        type,
                        actor,
                        item.status(),
                        to,
                        "KIND=" + item.kind(),
                        clock.instant());
        return storage.transition(item.agentId(), item.id(), from, to, event)
                ? Outcome.done(item.id())
                : Outcome.refused("NOT_" + from.iterator().next());
    }

    private interface Action {
        Outcome run(MemoryItem item);
    }

    /**
     * Human, tenant and existence checks shared by every decision; store errors become a refusal.
     */
    private Outcome guarded(
            UUID agentId, UUID itemId, MemoryActor actor, String operation, Action action) {
        if (agentId == null || itemId == null || actor == null || !actor.isIdentifiedHuman()) {
            return Outcome.refused("NOT_HUMAN");
        }
        if (!hasTenant()) {
            return Outcome.refused("NO_TENANT");
        }
        try {
            Optional<MemoryItem> item = storage.find(agentId, itemId);
            if (item.isEmpty()) {
                return Outcome.refused("NOT_FOUND");
            }
            return action.run(item.get());
        } catch (RuntimeException e) {
            log.warn("Memory review operation {} failed", operation, e);
            return Outcome.refused("ERROR");
        }
    }

    private Optional<String> screen(String text) {
        for (MemoryContentScreen screen : screens) {
            Optional<String> reason = screen.reject(text);
            if (reason.isPresent()) {
                return Optional.of("SCREEN_" + reason.get().replaceAll("[^A-Za-z0-9_]", "_"));
            }
        }
        return Optional.empty();
    }

    private static boolean hasTenant() {
        return TenantContext.current()
                .map(TenantContext::getTenantId)
                .filter(t -> !t.isBlank())
                .isPresent();
    }
}
