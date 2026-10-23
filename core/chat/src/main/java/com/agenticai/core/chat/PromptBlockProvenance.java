package com.agenticai.core.chat;

import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers, per (tenant, session), which pieces of the standing system text the latest turn put together and
 * where each came from (prompt-registry key, scope, version), so {@link LlmRequestSnapshotter} can label the
 * system messages of the model calls that follow. The turn's prompt assembler records it once per turn; the
 * snapshotter asks for every system message it copies.
 *
 * <p>Matching is by content: a block is attached to a system message that contains its exact text. If the text
 * was rewritten after assembly the block simply does not match and the message carries no label, so a label is
 * never guessed. Bounded like the snapshot store (least recently used sessions leave first, entries expire after
 * its time-to-live) and in this JVM only: the snapshot made from it is what is shared between replicas.
 */
public final class PromptBlockProvenance {

    /** One block as the assembler knows it. {@code text} is what was put in the message. */
    public record Block(
        String id, String key, String scope, String source, int version, String hash, String text, int estimatedTokens
    ) {}

    private record Entry(Instant at, List<Block> blocks) {}

    private static final PromptBlockProvenance INSTANCE = new PromptBlockProvenance();

    private volatile Duration ttl = Duration.ofHours(6);
    private volatile int maxSessions = 300;

    private final Map<String, Entry> bySession = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > maxSessions;
        }
    };

    private PromptBlockProvenance() {}

    static void configure(Duration ttl, int maxSessions) {
        INSTANCE.ttl = ttl;
        INSTANCE.maxSessions = Math.max(1, maxSessions);
    }

    private static String key(String tenantId, String sessionId) {
        return (tenantId == null ? "-" : tenantId) + "|" + sessionId;
    }

    /** Called by the prompt assembler for the calling thread's tenant; replaces what was recorded for the session. */
    public static void record(String sessionId, List<Block> blocks) {
        record(TenantContext.current().map(TenantContext::getTenantId).orElse(null), sessionId, blocks);
    }

    static void record(String tenantId, String sessionId, List<Block> blocks) {
        if (sessionId == null || blocks == null) {
            return;
        }
        List<Block> usable = new ArrayList<>(blocks.size());
        for (Block b : blocks) {
            if (b != null && b.text() != null && !b.text().isBlank()) {
                usable.add(b);
            }
        }
        synchronized (INSTANCE) {
            INSTANCE.bySession.put(key(tenantId, sessionId), new Entry(Instant.now(), List.copyOf(usable)));
        }
    }

    /** The recorded blocks whose text is inside {@code messageText}, in recorded order. Empty when none. */
    public static List<PromptBlockTag> tagsFor(String tenantId, String sessionId, String messageText) {
        if (sessionId == null || messageText == null || messageText.isEmpty()) {
            return List.of();
        }
        Entry entry;
        synchronized (INSTANCE) {
            entry = INSTANCE.bySession.get(key(tenantId, sessionId));
            if (entry != null && entry.at().isBefore(Instant.now().minus(INSTANCE.ttl))) {
                INSTANCE.bySession.remove(key(tenantId, sessionId));
                entry = null;
            }
        }
        if (entry == null) {
            return List.of();
        }
        List<PromptBlockTag> tags = new ArrayList<>();
        for (Block b : entry.blocks()) {
            if (messageText.contains(b.text())) {
                tags.add(new PromptBlockTag(
                    b.id(), b.key(), b.scope(), b.source(), b.version(), b.hash(), b.text().length(), b.estimatedTokens()));
            }
        }
        return List.copyOf(tags);
    }

    /** Forgets everything recorded (tests). */
    public static void clear() {
        synchronized (INSTANCE) {
            INSTANCE.bySession.clear();
        }
    }
}
