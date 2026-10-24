package com.agenticai.core.guardrails.taint;

import com.agenticai.core.prompts.PromptContext;
import com.agenticai.core.prompts.PromptResolver;
import java.util.UUID;

/**
 * "Spotlighting": delimits content from an untrusted source and tells the model, in the same
 * message, that the block is data and not instructions. A per-call random id in the delimiters
 * means content cannot forge its own closing marker. This is defence in depth only - the
 * enforceable control is the approval gate on side-effecting tools - but it makes an injected
 * instruction measurably less likely to be followed.
 */
public final class Spotlight {

    /**
     * Every wrapped block starts with this. It is NOT used to skip wrapping: the content is
     * attacker-controlled, so a page that began with this text would otherwise reach the model
     * undelimited.
     */
    static final String PREFIX = "[UNTRUSTED CONTENT";

    /**
     * Registry key of the banner line: {@code prompts/en/toolresult.untrusted_content.md} in
     * core/agents-turn, with {@code {tool}} for the tool name. The delimiters around the content are
     * not part of it and cannot be changed: they are what makes the block unforgeable.
     */
    public static final String BANNER_KEY = "toolresult.untrusted_content";

    private Spotlight() {}

    public static String wrap(String toolName, String content) {
        return wrap(toolName, content, null, null);
    }

    /**
     * As {@link #wrap(String, String)}, with the banner worded by {@code prompts} for {@code agentId}
     * (an agent, tenant or platform override of {@value #BANNER_KEY}); {@code null} = the shipped
     * wording. A banner that cannot be resolved is the shipped one: the content is never left
     * undelimited.
     */
    public static String wrap(String toolName, String content, PromptResolver prompts, UUID agentId) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        String tool = toolName == null ? "tool" : toolName.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return banner(tool, prompts, agentId) + "\n"
            + "<<<UNTRUSTED_DATA id=" + id + ">>>\n"
            + content
            + "\n<<<END_UNTRUSTED_DATA id=" + id + ">>>";
    }

    private static String banner(String tool, PromptResolver prompts, UUID agentId) {
        String text = null;
        if (prompts != null) {
            try {
                text = prompts.text(BANNER_KEY, PromptContext.forAgent(agentId).withVariable("tool", tool));
            } catch (RuntimeException e) {
                // fall through to the shipped wording
            }
        }
        if (text == null || text.isBlank()) {
            text = PromptResolver.defaultsOnly().text(BANNER_KEY, PromptContext.none().withVariable("tool", tool));
        }
        return text;
    }
}
