package com.agenticai.core.agents.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingInvalidException;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingType;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The shape of {@link ProductControlsCatalog}: no override means exactly the value of the property object the runtime reads
 * (moved off its default first, so a catalog that answered a default of its own would fail), every numeric entry is bounded
 * and refuses a value outside its bounds, and the scopes are the ones the use sites can honour.
 */
class ProductControlsCatalogTest {

    private final ProductControlsFixture f = new ProductControlsFixture();

    @AfterEach
    void clear() {
        SettingsRig.clear();
    }

    private Map<String, SettingDefinition> byKey() {
        return f.catalog().definitions().stream().collect(Collectors.toMap(SettingDefinition::key, d -> d));
    }

    @Test
    void everyKeyAnswersThePropertyItReplacedWhenNothingIsOverridden() {
        f.semanticCache.setSimilarityThreshold(0.9);
        f.semanticCache.setTtlHours(3);
        f.semanticCache.setMaxEntriesPerAgent(77);
        f.followUp.setMaxStickyTools(3);
        f.followUp.setKeepPrimaryModel(!f.followUp.isKeepPrimaryModel());
        f.ledger.setEnabled(!f.ledger.isEnabled());
        f.ledger.setReplayEnabled(!f.ledger.isReplayEnabled());
        f.ledger.setReplayTurns(4);
        f.ledger.setMaxEntriesPerTurn(12);
        f.ledger.setMaxReplayCharsPerTurn(1234);
        f.limits.getAttachments().setMaxImageBytes(5L * 1024 * 1024);
        f.limits.getAttachments().setMaxAudioBytes(6L * 1024 * 1024);
        f.limits.getAttachments().setMaxVideoBytes(7L * 1024 * 1024);
        f.limits.getAttachments().setMaxDocumentBytes(9L * 1024 * 1024);
        f.memory.setTenantMaxMemories(123);
        f.memory.setEdgeMemoryMaxFacts(17);

        Map<String, Object> expected = Map.ofEntries(
                Map.entry("chat.semanticCache.similarityThreshold", 0.9),
                Map.entry("chat.semanticCache.ttl", Duration.ofHours(3).toSeconds()),
                Map.entry("chat.semanticCache.maxEntriesPerAgent", 77L),
                Map.entry("chat.followUp.maxStickyTools", 3L),
                Map.entry("chat.followUp.keepPrimaryModel", f.followUp.isKeepPrimaryModel()),
                Map.entry("chat.toolLedger.enabled", f.ledger.isEnabled()),
                Map.entry("chat.toolLedger.replayEnabled", f.ledger.isReplayEnabled()),
                Map.entry("chat.toolLedger.replayTurns", 4L),
                Map.entry("chat.toolLedger.maxEntriesPerTurn", 12L),
                Map.entry("chat.toolLedger.maxReplayCharsPerTurn", 1234L),
                Map.entry("chat.attachments.maxImageMb", 5L),
                Map.entry("chat.attachments.maxAudioMb", 6L),
                Map.entry("chat.attachments.maxVideoMb", 7L),
                Map.entry("chat.attachments.maxDocumentMb", 9L),
                Map.entry("memory.tenant.maxMemories", 123L),
                Map.entry("memory.edge.maxFacts", 17L));
        Map<String, SettingDefinition> defs = byKey();
        assertThat(defs.keySet()).isEqualTo(expected.keySet());
        expected.forEach((key, value) -> assertThat(defs.get(key).platformValue()).as(key).isEqualTo(value));
    }

    @Test
    void theShippedDefaultsAreInsideTheirOwnBoundsAndAcceptedByTheirOwnValidation() {
        for (SettingDefinition d : f.catalog().definitions()) {
            assertThat(d.normalize(d.platformValue())).as(d.key()).isEqualTo(d.platformValue());
            if (d.type().isNumeric()) {
                assertThat(d.min()).as(d.key()).isNotNull();
                assertThat(d.max()).as(d.key()).isNotNull();
            }
        }
    }

    @Test
    void everyNumericEntryRefusesAValueOutsideItsBounds() {
        for (SettingDefinition d : f.catalog().definitions()) {
            if (!d.type().isNumeric()) {
                continue;
            }
            double belowRaw = d.min() - (d.type() == SettingType.DECIMAL ? 0.01 : 1);
            double aboveRaw = d.max() + (d.type() == SettingType.DECIMAL ? 0.01 : 1);
            Object below = d.type() == SettingType.DECIMAL ? (Object) belowRaw : (Object) (long) belowRaw;
            Object above = d.type() == SettingType.DECIMAL ? (Object) aboveRaw : (Object) (long) aboveRaw;
            assertThatThrownBy(() -> d.normalize(below)).as(d.key() + " below").isInstanceOf(SettingInvalidException.class);
            assertThatThrownBy(() -> d.normalize(above)).as(d.key() + " above").isInstanceOf(SettingInvalidException.class);
        }
    }

    @Test
    void theToolRecordAndTheWorkspaceMemoryCapBelongToTheWorkspaceAndTheRestMayBeSetPerAgent() {
        Set<String> workspaceOnly = Set.of(
                "chat.toolLedger.enabled", "chat.toolLedger.replayEnabled", "chat.toolLedger.replayTurns",
                "chat.toolLedger.maxEntriesPerTurn", "chat.toolLedger.maxReplayCharsPerTurn", "memory.tenant.maxMemories");
        for (SettingDefinition d : f.catalog().definitions()) {
            if (workspaceOnly.contains(d.key())) {
                assertThat(d.scopes()).as(d.key()).containsExactly(SettingScope.TENANT);
            } else {
                assertThat(d.scopes()).as(d.key()).containsExactlyInAnyOrder(SettingScope.TENANT, SettingScope.AGENT);
            }
        }
    }

    @Test
    void anUploadSizeThatIsNotAWholeNumberOfMegabytesReadsAsTheNextWholeOneAndNeverZero() {
        f.limits.getAttachments().setMaxImageBytes(1);
        f.limits.getAttachments().setMaxAudioBytes(1024L * 1024 + 1);
        assertThat(byKey().get("chat.attachments.maxImageMb").platformValue()).isEqualTo(1L);
        assertThat(byKey().get("chat.attachments.maxAudioMb").platformValue()).isEqualTo(2L);
    }

    @Test
    void aTenantOverrideReachesTheResolverForTheTenantAndItsAgentsAndNoOtherTenant() {
        UUID agent = UUID.randomUUID();
        f.rig().tenant("tenant-a", "chat.followUp.maxStickyTools", 9)
                .agent("tenant-a", agent, "chat.followUp.maxStickyTools", 2);
        SettingsRig.inTenant("tenant-a", () -> {
            assertThat(f.rig().resolver().getInt("chat.followUp.maxStickyTools", agent)).isEqualTo(2);
            assertThat(f.rig().resolver().getInt("chat.followUp.maxStickyTools", UUID.randomUUID())).isEqualTo(9);
        });
        SettingsRig.inTenant("tenant-b", () ->
                assertThat(f.rig().resolver().getInt("chat.followUp.maxStickyTools", agent))
                        .isEqualTo(f.followUp.getMaxStickyTools()));
    }
}
