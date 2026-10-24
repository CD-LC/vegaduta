package com.agenticai.core.guardrails;

import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingsResolver;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the guardrail checks read from the settings catalog ({@code docs/settings.md}): the strictness knobs a tenant admin
 * or an agent editor may tighten for their own workspace or agent. The use sites ({@link GuardedToolCallback}, the judges,
 * the toxicity and jailbreak guardrails) are built with {@code new} on many paths, so, like {@link GuardrailFailMode}, the
 * resolver is held here and set once at startup by {@link GuardrailSettingsConfiguration}; with none set (a plain unit
 * test, an embedded use) every method answers the platform value, which is exactly what the code did before the catalog.
 *
 * <p><b>These are security settings: an override may only make a check stricter.</b> The catalog refuses a weaker value on
 * write and the resolver takes the stricter layer on read; this class does a third, independent check at the use site:
 * whatever the resolver answers, the result is never weaker than the platform value (the property object the runtime
 * reads, i.e. the deployment's yml), and a weaker answer is ignored with a warning. So a row written around the API, a
 * resolver bug or a platform value tightened after an override was stored cannot loosen a guardrail.
 *
 * <p>If the resolver fails for any reason the platform value is used (and a warning logged once): a settings outage never
 * switches a guardrail off and never stops a turn.
 *
 * <p>The tenant is the calling thread's {@code TenantContext}; the agent is the argument, or, where the caller does not
 * know it, the one in {@link GuardrailPromptScope} (the same scope the judge prompts use), or none (tenant level).
 */
public final class GuardrailSettings {

    private static final Logger log = LoggerFactory.getLogger(GuardrailSettings.class);

    public static final String FAIL_MODE = "guardrails.failMode";
    public static final String TOOL_VERIFIER_FAIL_MODE = "guardrails.toolVerifier.failMode";
    public static final String TOXICITY_BLOCK_SCORE = "guardrails.toxicity.blockScore";
    public static final String JAILBREAK_MAX_SCANNED_CHARS = "guardrails.jailbreak.maxScannedChars";
    public static final String JUDGE_MAX_INPUT_CHARS = "guardrails.judge.maxInputChars";
    /** Enum, least to most strict: {@link #SUPERVISED_AUTO_ALLOW} then {@link #SUPERVISED_GATE}. */
    public static final String SUPERVISED_READ_ONLY_CALLS = "guardrails.supervised.readOnlyCalls";
    public static final String SUPERVISED_AUTO_ALLOW = "auto-allow";
    public static final String SUPERVISED_GATE = "gate";

    private static final int MAX_WARNED = 256;

    private static volatile SettingsResolver resolver;

    /** Warnings already logged (key plus detail), so a stored value that stays weaker does not log on every call. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private GuardrailSettings() {}

    // ------------------------------------------------------------------------------------------ wiring

    /** Startup hook (and tests): the resolver the guardrails read; null = platform values only. */
    public static void use(SettingsResolver settingsResolver) {
        resolver = settingsResolver;
        WARNED.clear();
    }

    /** Detaches {@code settingsResolver} if it is still the current one (a closing application context). */
    public static void release(SettingsResolver settingsResolver) {
        if (resolver == settingsResolver) {
            resolver = null;
        }
    }

    /** Test hook: back to platform values only. */
    public static void clear() {
        resolver = null;
        WARNED.clear();
    }

    // ------------------------------------------------------------------------------------------ fail mode

    /** The fail mode in force for the current agent scope ({@code guardrails.failMode}); never looser than the platform's. */
    public static GuardrailFailMode failMode() {
        return failMode(GuardrailPromptScope.currentAgent());
    }

    public static GuardrailFailMode failMode(UUID agentId) {
        GuardrailFailMode platform = GuardrailFailMode.current();
        String resolved = readString(FAIL_MODE, agentId);
        if (resolved == null) {
            return platform;
        }
        GuardrailFailMode wanted = GuardrailFailMode.parse(resolved);
        return stricter(FAIL_MODE, platform, wanted);
    }

    /** True when an infrastructure failure of a guardrail refuses the work: the replacement for {@link GuardrailFailMode#isClosed()}. */
    public static boolean failClosed() {
        return failMode() == GuardrailFailMode.CLOSED;
    }

    public static boolean failClosed(UUID agentId) {
        return failMode(agentId) == GuardrailFailMode.CLOSED;
    }

    /**
     * What the tool-call verifier does when it cannot give a verdict ({@code guardrails.toolVerifier.failMode}). {@code
     * configured} is the platform's setting (the property object); {@code inherit} follows {@link #failMode()}, so a
     * workspace that closes the global mode closes an inheriting verifier too. Never looser than the platform's.
     */
    public static GuardrailFailMode toolVerifierFailMode(GuardrailTuningProperties.ToolVerifier configured) {
        return toolVerifierFailMode(configured, GuardrailPromptScope.currentAgent());
    }

    public static GuardrailFailMode toolVerifierFailMode(GuardrailTuningProperties.ToolVerifier configured, UUID agentId) {
        GuardrailFailMode platform = configured.effective();
        String resolved = readString(TOOL_VERIFIER_FAIL_MODE, agentId);
        if (resolved == null) {
            return platform;
        }
        GuardrailFailMode wanted;
        switch (resolved.trim().toLowerCase(Locale.ROOT)) {
            case "open":
                wanted = GuardrailFailMode.OPEN;
                break;
            case "closed":
                wanted = GuardrailFailMode.CLOSED;
                break;
            default: // "inherit": follows the (stricter-wins) global mode of this workspace and agent
                wanted = failMode(agentId);
                break;
        }
        return stricter(TOOL_VERIFIER_FAIL_MODE, platform, wanted);
    }

    // ------------------------------------------------------------------------------------------ supervised tier

    /**
     * Whether the SUPERVISED autonomy tier still asks a human before a classified read-only tool call
     * ({@code app.guardrails.supervised.gate-read-only}, {@code guardrails.supervised.readOnlyCalls}). True gates it (every
     * call), false lets it through. A workspace or an agent may only turn a platform {@code false} back to {@code true},
     * so this is never looser than the platform value.
     */
    public static boolean supervisedGatesReadOnly(GuardrailTuningProperties tuning, UUID agentId) {
        boolean platform = tuning.getSupervised().isGateReadOnly();
        if (platform) {
            return true;
        }
        String resolved = readString(SUPERVISED_READ_ONLY_CALLS, agentId);
        if (resolved == null) {
            return false;
        }
        // Anything but a recognised "auto-allow" gates: an unreadable or unknown value must not switch a gate off.
        return !SUPERVISED_AUTO_ALLOW.equalsIgnoreCase(resolved.trim());
    }

    // ------------------------------------------------------------------------------------------ numbers

    /** The judge's 0-100 toxicity score at or above which content is blocked; lower is stricter, so never above the platform's. */
    public static int toxicityBlockScore(GuardrailTuningProperties tuning) {
        return toxicityBlockScore(tuning, GuardrailPromptScope.currentAgent());
    }

    public static int toxicityBlockScore(GuardrailTuningProperties tuning, UUID agentId) {
        int platform = tuning.getToxicity().getBlockScore();
        Integer resolved = readInt(TOXICITY_BLOCK_SCORE, agentId);
        if (resolved == null) {
            return platform;
        }
        if (resolved > platform) {
            ignoredWeaker(TOXICITY_BLOCK_SCORE, resolved, platform);
            return platform;
        }
        return resolved;
    }

    /** How many characters the jailbreak patterns scan; higher is stricter, so never below the platform's. */
    public static int jailbreakMaxScannedChars(GuardrailTuningProperties tuning) {
        return jailbreakMaxScannedChars(tuning, GuardrailPromptScope.currentAgent());
    }

    public static int jailbreakMaxScannedChars(GuardrailTuningProperties tuning, UUID agentId) {
        int platform = tuning.getJailbreak().getMaxScannedChars();
        Integer resolved = readInt(JAILBREAK_MAX_SCANNED_CHARS, agentId);
        if (resolved == null) {
            return platform;
        }
        if (resolved < platform) {
            ignoredWeaker(JAILBREAK_MAX_SCANNED_CHARS, resolved, platform);
            return platform;
        }
        return resolved;
    }

    /** How many characters of the checked text a judge sees; higher is stricter, so never below the platform's. */
    public static int judgeMaxInputChars(GuardrailTuningProperties tuning) {
        return judgeMaxInputChars(tuning, GuardrailPromptScope.currentAgent());
    }

    public static int judgeMaxInputChars(GuardrailTuningProperties tuning, UUID agentId) {
        int platform = tuning.getJudge().getMaxInputChars();
        Integer resolved = readInt(JUDGE_MAX_INPUT_CHARS, agentId);
        if (resolved == null) {
            return platform;
        }
        if (resolved < platform) {
            ignoredWeaker(JUDGE_MAX_INPUT_CHARS, resolved, platform);
            return platform;
        }
        return resolved;
    }

    /** {@code text} cut to the judge window in force for the current agent scope, plus the configured marker. */
    public static String truncateForJudge(GuardrailTuningProperties tuning, String text) {
        return tuning.getJudge().truncate(text, judgeMaxInputChars(tuning));
    }

    // ------------------------------------------------------------------------------------------ internals

    private static GuardrailFailMode stricter(String key, GuardrailFailMode platform, GuardrailFailMode wanted) {
        if (platform == GuardrailFailMode.CLOSED && wanted == GuardrailFailMode.OPEN) {
            ignoredWeaker(key, wanted, platform);
            return platform;
        }
        return wanted;
    }

    private static String readString(String key, UUID agentId) {
        SettingsResolver r = resolver;
        if (r == null) {
            return null;
        }
        try {
            Object value = r.get(key, SettingContext.forAgent(agentId)).value();
            return value == null ? null : String.valueOf(value);
        } catch (RuntimeException e) {
            unreadable(key, e);
            return null;
        }
    }

    private static Integer readInt(String key, UUID agentId) {
        SettingsResolver r = resolver;
        if (r == null) {
            return null;
        }
        try {
            return r.getInt(key, SettingContext.forAgent(agentId));
        } catch (RuntimeException e) {
            unreadable(key, e);
            return null;
        }
    }

    private static void unreadable(String key, RuntimeException e) {
        if (shouldWarn(key + "|unreadable")) {
            log.warn("Could not read setting {}; using the platform value: {}", key, e.toString());
        }
    }

    private static void ignoredWeaker(String key, Object stored, Object platform) {
        if (shouldWarn(key + "|" + stored + "|" + platform)) {
            log.warn("Ignoring setting {} = {}: it is weaker than the platform value {}. A guardrail setting can only be made "
                    + "stricter than the deployment's value; the platform value applies.", key, stored, platform);
        }
    }

    private static boolean shouldWarn(String id) {
        if (WARNED.size() >= MAX_WARNED) {
            WARNED.clear();
        }
        return WARNED.add(id);
    }
}
