package com.agenticai.core.agents.turn;

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

    static final String TEXT = """
        When you produce a self-contained deliverable the user will keep, run or edit (a web page or app, an SVG, a document, a code file, a Mermaid diagram, a CSV table), put it in ONE fenced block whose info string is artifact:<type>:<title>, with type one of html, svg, markdown, code:<lang>, mermaid, csv - for example ```artifact:html:Pricing page or ```artifact:code:python:Fizzbuzz. Put the COMPLETE content inside, never a diff or placeholder; HTML must be one self-contained file (inline CSS and JS, no external resources, no network). To revise it, reuse the exact same title and send the whole new version. If the content itself contains ``` fences, open and close the block with four backticks. Add at most a sentence or two outside the block. Do not use this for short snippets or ordinary answers.""";

    /** The directive for this agent, or {@code null} when it does not apply (the common case). */
    static String forAgent(boolean enabled, boolean allAgents, String agentIdsCsv, UUID agentId) {
        if (!enabled) {
            return null;
        }
        return (allAgents || isListed(agentIdsCsv, agentId)) ? TEXT : null;
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
