package com.agenticai.core.agents.turn;

import com.agenticai.core.prompts.PromptResolver;
import java.util.UUID;

/**
 * The standing directive that teaches the model the artifacts fenced-block convention the web
 * console's Artifacts pane renders (web/app/lib/artifacts/artifactParser.ts is the other half of
 * the contract: keep the two in step).
 *
 * <p><b>Cost posture.</b> Opt-in on two independent levels, so default prompts stay byte-identical
 * to today:
 * <ol>
 *   <li>the platform kill-switch {@code app.chat.directives.artifacts.enabled} (default
 *       <b>false</b>);</li>
 *   <li>per agent: either {@code app.chat.directives.artifacts.all-agents=true} or the agent's id
 *       is listed in {@code app.chat.directives.artifacts.agent-ids}.</li>
 * </ol>
 * The text below is a byte-stable constant (no per-turn interpolation) and is added to the per-turn
 * block-2 SystemMessage like the other directives, never to the agent's own system prompt.
 *
 * <p><b>Why config and not an agent column.</b> The per-agent toggle here is deliberately
 * operator configuration: a persisted, tenant-editable per-agent switch needs a new {@code agents}
 * column (Flyway) plus DTO/UI work, which this lane did not have a migration number for. When that
 * lands, only {@link #forAgent} changes.
 *
 * <p><b>Not applied to inner calls.</b> Agent-to-agent delegation and specialist handoff return text
 * to an outer agent rather than to the renderer, so the convention is not taught there (the
 * delegation directive block only carries safety rails).
 */
final class ArtifactsDirective {

    private ArtifactsDirective() {
    }

    static final String TEXT =
            PromptResolver.defaultsOnly().catalog().defaultText(PromptDirectiveRegistry.Keys.ARTIFACTS);

    /** The directive for this agent, or {@code null} when it does not apply (the common case). */
    static String forAgent(boolean enabled, boolean allAgents, String agentIdsCsv, UUID agentId) {
        return keyForAgent(enabled, allAgents, agentIdsCsv, agentId) == null ? null : TEXT;
    }

    /** {@code directive.artifacts} when the directive applies to this agent, else {@code null}. */
    static String keyForAgent(boolean enabled, boolean allAgents, String agentIdsCsv, UUID agentId) {
        if (!enabled) {
            return null;
        }
        return (allAgents || isListed(agentIdsCsv, agentId)) ? PromptDirectiveRegistry.Keys.ARTIFACTS : null;
    }

    /** True when {@code agentId} appears in the comma-separated list (case-insensitive, whitespace-tolerant). */
    static boolean isListed(String agentIdsCsv, UUID agentId) {
        if (agentIdsCsv == null || agentIdsCsv.isBlank() || agentId == null) {
            return false;
        }
        String wanted = agentId.toString();
        for (String part : agentIdsCsv.split(",")) {
            if (wanted.equalsIgnoreCase(part.trim())) {
                return true;
            }
        }
        return false;
    }
}
