package com.agenticai.core.guardrails;

import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingRisk;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingType;
import com.agenticai.core.settings.SettingUnit;
import com.agenticai.core.settings.Strictness;
import java.util.List;
import java.util.Locale;

/**
 * The settings-catalog entries for the guardrail strictness knobs, declared next to the code that reads them
 * ({@link GuardrailSettings}). All are risk {@code security}: a tenant admin only, with a typed reason, and only ever
 * stricter than the platform value. The platform value of each is read from the property object the guardrails use
 * ({@link GuardrailTuningProperties}, {@link GuardrailFailMode}), so the catalog default cannot drift from the yml.
 *
 * <p>Registered through {@code BehaviourSettingsCatalog} (agents-turn), which assembles every behaviour entry whose
 * properties live at or below that module.
 */
public final class GuardrailSettingDefinitions {

    private static final SettingScope[] TENANT_AND_AGENT = {SettingScope.TENANT, SettingScope.AGENT};

    private GuardrailSettingDefinitions() {}

    public static List<SettingDefinition> all(GuardrailTuningProperties guardrails) {
        return List.of(
                SettingDefinition.builder(GuardrailSettings.FAIL_MODE, "guardrails", SettingType.ENUM)
                        .enumValues("open", "closed").risk(SettingRisk.SECURITY).strictness(Strictness.ENUM_ORDER)
                        .scopes(TENANT_AND_AGENT).property("app.guardrails.fail-mode").docsAnchor("behaviour-settings")
                        .platform(() -> GuardrailFailMode.current().name().toLowerCase(Locale.ROOT)).build(),
                SettingDefinition.builder(GuardrailSettings.TOOL_VERIFIER_FAIL_MODE, "guardrails", SettingType.ENUM)
                        .enumValues("open", "inherit", "closed").risk(SettingRisk.SECURITY).strictness(Strictness.ENUM_ORDER)
                        .scopes(TENANT_AND_AGENT).property("app.guardrails.tool-verifier.fail-mode").docsAnchor("behaviour-settings")
                        .platform(() -> guardrails.getToolVerifier().getFailMode()).build(),
                SettingDefinition.builder(GuardrailSettings.TOXICITY_BLOCK_SCORE, "guardrails", SettingType.INT)
                        .range(0, 100).risk(SettingRisk.SECURITY).scopes(TENANT_AND_AGENT)
                        .property("app.guardrails.toxicity.block-score").docsAnchor("behaviour-settings")
                        .strictness(Strictness.LOWER_IS_STRICTER)
                        .platform(() -> guardrails.getToxicity().getBlockScore()).build(),
                SettingDefinition.builder(GuardrailSettings.JAILBREAK_MAX_SCANNED_CHARS, "guardrails", SettingType.INT)
                        .unit(SettingUnit.CHARS).range(1_000, 200_000).risk(SettingRisk.SECURITY).scopes(TENANT_AND_AGENT)
                        .property("app.guardrails.jailbreak.max-scanned-chars").docsAnchor("behaviour-settings")
                        .strictness(Strictness.HIGHER_IS_STRICTER)
                        .platform(() -> guardrails.getJailbreak().getMaxScannedChars()).build(),
                SettingDefinition.builder(GuardrailSettings.JUDGE_MAX_INPUT_CHARS, "guardrails", SettingType.INT)
                        .unit(SettingUnit.CHARS).range(500, 100_000).risk(SettingRisk.SECURITY).scopes(TENANT_AND_AGENT)
                        .property("app.guardrails.judge.max-input-chars").docsAnchor("behaviour-settings")
                        .strictness(Strictness.HIGHER_IS_STRICTER)
                        .platform(() -> guardrails.getJudge().getMaxInputChars()).build(),
                // Enum rather than a bool: the catalog orders an enum from least to most strict, and has no such order for
                // a bool. "gate" (a human approves every call of a SUPERVISED agent) is the stricter side.
                SettingDefinition.builder(GuardrailSettings.SUPERVISED_READ_ONLY_CALLS, "guardrails", SettingType.ENUM)
                        .enumValues(GuardrailSettings.SUPERVISED_AUTO_ALLOW, GuardrailSettings.SUPERVISED_GATE)
                        .risk(SettingRisk.SECURITY).strictness(Strictness.ENUM_ORDER).scopes(TENANT_AND_AGENT)
                        .property("app.guardrails.supervised.gate-read-only").docsAnchor("behaviour-settings")
                        .platform(() -> guardrails.getSupervised().isGateReadOnly()
                                ? GuardrailSettings.SUPERVISED_GATE : GuardrailSettings.SUPERVISED_AUTO_ALLOW)
                        .build());
    }
}
