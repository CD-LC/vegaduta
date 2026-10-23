package com.agenticai.core.agents.settings;

import com.agenticai.core.agents.memory.MemoryLimitsProperties;
import com.agenticai.core.agents.turn.ChatLimitsProperties;
import com.agenticai.core.agents.turn.FollowUpProperties;
import com.agenticai.core.agents.turn.ToolLedgerProperties;
import com.agenticai.core.agents.turn.cache.SemanticCacheProperties;
import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingRisk;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingType;
import com.agenticai.core.settings.SettingUnit;
import com.agenticai.core.settings.SettingsContributor;
import java.time.Duration;
import java.util.List;

/**
 * The owner-visible product controls that used to be deployment-only properties: the semantic response cache, the size
 * of a chat upload of each kind, how a short follow-up keeps the conversation's tools and model, how many shared and edge
 * memories are kept, and the tool record a turn leaves in the conversation. Each entry reads its platform value from the
 * SAME property object the runtime reads; the use site that makes it take effect is named in {@code docs/settings.md}.
 * The step-limit numbers are in {@code StepLimitSettings} and the skill-index size in {@code SkillPromptSettings}, next to
 * the code that reads them.
 *
 * <p>Upload sizes are whole megabytes (the platform properties are bytes): an operator's value that is not a whole number
 * of megabytes is kept exactly until a tenant or an agent sets its own. No entry here is a security entry; the bounds keep
 * every value inside what the platform itself bounds (an upload is also capped by {@code
 * spring.servlet.multipart.max-file-size}).
 */
public class ProductControlsCatalog implements SettingsContributor {

    private static final SettingScope[] TENANT_AND_AGENT = {SettingScope.TENANT, SettingScope.AGENT};
    private static final SettingScope[] TENANT_ONLY = {SettingScope.TENANT};
    private static final long MB = 1024L * 1024L;

    private final SemanticCacheProperties semanticCache;
    private final ChatLimitsProperties.Attachments attachments;
    private final FollowUpProperties followUp;
    private final MemoryLimitsProperties memory;
    private final ToolLedgerProperties ledger;

    public ProductControlsCatalog(
            SemanticCacheProperties semanticCache,
            ChatLimitsProperties limits,
            FollowUpProperties followUp,
            MemoryLimitsProperties memory,
            ToolLedgerProperties ledger) {
        this.semanticCache = semanticCache;
        this.attachments = limits.getAttachments();
        this.followUp = followUp;
        this.memory = memory;
        this.ledger = ledger;
    }

    private static SettingDefinition.Builder number(
            String key, String group, SettingType type, SettingUnit unit, double min, double max, SettingRisk risk, String property,
            SettingScope... scopes) {
        SettingDefinition.Builder b = SettingDefinition.builder(key, group, type)
                .range(min, max).risk(risk).scopes(scopes).property(property).docsAnchor("behaviour-settings");
        return unit == null ? b : b.unit(unit);
    }

    private static SettingDefinition.Builder bool(String key, String group, SettingRisk risk, String property, SettingScope... scopes) {
        return SettingDefinition.builder(key, group, SettingType.BOOL).risk(risk).scopes(scopes).property(property).docsAnchor("behaviour-settings");
    }

    /** Whole megabytes, rounded up so a platform value below one megabyte still reads as a valid 1. */
    private static Object megabytes(long bytes) {
        return Math.max(1L, (bytes + MB - 1) / MB);
    }

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                // ---- semantic response cache (opt-in per agent)
                number("chat.semanticCache.similarityThreshold", "chat", SettingType.DECIMAL, null, 0.85, 1.0, SettingRisk.MEDIUM,
                                "app.chat.semantic-cache.similarity-threshold", TENANT_AND_AGENT)
                        .step(0.01).platform(semanticCache::getSimilarityThreshold).build(),
                number("chat.semanticCache.ttl", "chat", SettingType.DURATION, SettingUnit.S, 300, 30 * 86_400, SettingRisk.LOW,
                                "app.chat.semantic-cache.ttl-hours", TENANT_AND_AGENT)
                        .platform(() -> Duration.ofHours(semanticCache.getTtlHours())).build(),
                number("chat.semanticCache.maxEntriesPerAgent", "chat", SettingType.INT, SettingUnit.COUNT, 10, 5_000, SettingRisk.LOW,
                                "app.chat.semantic-cache.max-entries-per-agent", TENANT_AND_AGENT)
                        .platform(semanticCache::getMaxEntriesPerAgent).build(),

                // ---- follow-ups
                number("chat.followUp.maxStickyTools", "chat", SettingType.INT, SettingUnit.COUNT, 0, 20, SettingRisk.LOW,
                                "app.chat.follow-up.max-sticky-tools", TENANT_AND_AGENT)
                        .platform(followUp::getMaxStickyTools).build(),
                bool("chat.followUp.keepPrimaryModel", "chat", SettingRisk.LOW, "app.chat.follow-up.keep-primary-model", TENANT_AND_AGENT)
                        .platform(followUp::isKeepPrimaryModel).build(),

                // ---- tool record kept with the conversation (workspace-wide: the session history is the workspace's)
                bool("chat.toolLedger.enabled", "chat", SettingRisk.LOW, "app.chat.tool-ledger.enabled", TENANT_ONLY)
                        .platform(ledger::isEnabled).build(),
                bool("chat.toolLedger.replayEnabled", "chat", SettingRisk.LOW, "app.chat.tool-ledger.replay-enabled", TENANT_ONLY)
                        .platform(ledger::isReplayEnabled).build(),
                number("chat.toolLedger.replayTurns", "chat", SettingType.INT, SettingUnit.COUNT, 0, 20, SettingRisk.LOW,
                                "app.chat.tool-ledger.replay-turns", TENANT_ONLY)
                        .platform(ledger::getReplayTurns).build(),
                number("chat.toolLedger.maxEntriesPerTurn", "chat", SettingType.INT, SettingUnit.COUNT, 0, 100, SettingRisk.LOW,
                                "app.chat.tool-ledger.max-entries-per-turn", TENANT_ONLY)
                        .platform(ledger::getMaxEntriesPerTurn).build(),
                number("chat.toolLedger.maxReplayCharsPerTurn", "chat", SettingType.INT, SettingUnit.CHARS, 200, 10_000, SettingRisk.LOW,
                                "app.chat.tool-ledger.max-replay-chars-per-turn", TENANT_ONLY)
                        .platform(ledger::getMaxReplayCharsPerTurn).build(),

                // ---- chat uploads, in whole megabytes
                number("chat.attachments.maxImageMb", "attachments", SettingType.INT, null, 1, 30, SettingRisk.MEDIUM,
                                "app.chat.limits.attachments.max-image-bytes", TENANT_AND_AGENT)
                        .platform(() -> megabytes(attachments.getMaxImageBytes())).build(),
                number("chat.attachments.maxAudioMb", "attachments", SettingType.INT, null, 1, 30, SettingRisk.MEDIUM,
                                "app.chat.limits.attachments.max-audio-bytes", TENANT_AND_AGENT)
                        .platform(() -> megabytes(attachments.getMaxAudioBytes())).build(),
                number("chat.attachments.maxVideoMb", "attachments", SettingType.INT, null, 1, 30, SettingRisk.MEDIUM,
                                "app.chat.limits.attachments.max-video-bytes", TENANT_AND_AGENT)
                        .platform(() -> megabytes(attachments.getMaxVideoBytes())).build(),
                number("chat.attachments.maxDocumentMb", "attachments", SettingType.INT, null, 1, 30, SettingRisk.MEDIUM,
                                "app.chat.limits.attachments.max-document-bytes", TENANT_AND_AGENT)
                        .platform(() -> megabytes(attachments.getMaxDocumentBytes())).build(),

                // ---- memory
                number("memory.tenant.maxMemories", "memory", SettingType.INT, SettingUnit.COUNT, 10, 5_000, SettingRisk.MEDIUM,
                                "app.memory.limits.tenant-max-memories", TENANT_ONLY)
                        .platform(memory::getTenantMaxMemories).build(),
                number("memory.edge.maxFacts", "memory", SettingType.INT, SettingUnit.COUNT, 1, 100, SettingRisk.LOW,
                                "app.memory.limits.edge-memory-max-facts", TENANT_AND_AGENT)
                        .platform(memory::getEdgeMemoryMaxFacts).build());
    }
}
