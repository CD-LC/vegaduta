package com.agenticai.core.quality;

import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingRisk;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingType;
import com.agenticai.core.settings.SettingUnit;
import com.agenticai.core.settings.SettingsContributor;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Settings-catalog entries for evaluation and judging ({@code quality.*}). The platform values are the
 * {@code app.quality.eval.*} keys the eval services read with {@code @Value}; the defaults here are the same literals,
 * and {@code ConfigurationDriftTest} keeps both equal to {@code application.yml}.
 */
@Component
public class QualitySettings implements SettingsContributor {

    public static final String JUDGE_MODEL = "quality.judge.model";
    public static final String JUDGE_CALL_TIMEOUT_SECONDS = "quality.judge.callTimeoutSeconds";
    public static final String EVAL_DEFAULT_REPEATS = "quality.eval.defaultRepeats";
    public static final String EVAL_DEFAULT_MODE = "quality.eval.defaultMode";

    private final Environment env;

    public QualitySettings(Environment env) {
        this.env = env;
    }

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                SettingDefinition.builder(JUDGE_MODEL, "quality", SettingType.STRING)
                        .max(120).risk(SettingRisk.MEDIUM).scopes(SettingScope.TENANT)
                        .platform(() -> env.getProperty("app.quality.eval.judge-model", ""))
                        .property("app.quality.eval.judge-model").docsAnchor("behaviour-settings").build(),
                SettingDefinition.builder(JUDGE_CALL_TIMEOUT_SECONDS, "quality", SettingType.DURATION)
                        .unit(SettingUnit.S).range(5, 300).risk(SettingRisk.LOW).scopes(SettingScope.TENANT)
                        .platform(() -> env.getProperty("app.quality.eval.judge-call-timeout-seconds", Long.class, 20L))
                        .property("app.quality.eval.judge-call-timeout-seconds").docsAnchor("behaviour-settings").build(),
                SettingDefinition.builder(EVAL_DEFAULT_REPEATS, "quality", SettingType.INT)
                        .unit(SettingUnit.COUNT).range(1, 5).risk(SettingRisk.MEDIUM).scopes(SettingScope.TENANT)
                        .platform(() -> env.getProperty("app.quality.eval.default-repeats", Integer.class, 3))
                        .property("app.quality.eval.default-repeats").docsAnchor("behaviour-settings").build(),
                // Not a security setting: it only chooses what an eval measures (the production pipeline in the eval
                // sandbox, or a text-only quick replay) and what a run costs. The sandbox itself (what may really run,
                // who may auto-approve) is not reachable from here.
                SettingDefinition.builder(EVAL_DEFAULT_MODE, "quality", SettingType.ENUM)
                        .enumValues("full", "quick").risk(SettingRisk.MEDIUM).scopes(SettingScope.TENANT)
                        .platform(() -> env.getProperty("app.quality.eval.default-mode", "full").trim().toLowerCase(java.util.Locale.ROOT))
                        .property("app.quality.eval.default-mode").docsAnchor("behaviour-settings").build());
    }
}
