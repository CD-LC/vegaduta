package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.tunables.BehaviourSettings;
import com.agenticai.core.security.TenantScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * What a turn may do with the response caches ({@code app.chat.cache.*}): whether it may use them at all, and
 * the conversation key that goes into their keys.
 *
 * <p>The key is a digest of the newest {@code context-turns} turns before the message, so a first message
 * (nothing before it) keys on the message alone - an FAQ still hits across conversations - while "yes" after
 * one question is a different entry from "yes" after another. The tool record the model is replayed with is
 * not part of the key: it is the same conversation whether or not a tool ran, and an agent whose turns may be
 * cached has no tools to record.
 */
public final class ChatCachePolicy {

    public static final String ELIGIBLE_WITH_TOOLS_KEY = "chat.cache.eligibleWithTools";
    public static final String CONTEXT_TURNS_KEY = "chat.cache.contextTurns";

    private final ChatCacheProperties properties;
    private final BehaviourSettings behaviour;

    public ChatCachePolicy(ChatCacheProperties properties) {
        this(properties, BehaviourSettings.platformOnly());
    }

    /**
     * @param behaviour where a tenant's or an agent's {@code chat.cache.eligibleWithTools} / {@code
     *     chat.cache.contextTurns} come from; the platform values ({@code properties}) when it has no catalog
     */
    public ChatCachePolicy(ChatCacheProperties properties, BehaviourSettings behaviour) {
        this.properties = properties;
        this.behaviour = behaviour == null ? BehaviourSettings.platformOnly() : behaviour;
    }

    /** True when a turn of an agent that offers tools may still use the caches (the operator opted in). */
    boolean eligibleWithTools() {
        return properties.isEligibleWithTools();
    }

    /** As {@link #eligibleWithTools()} for {@code agentId} of the tenant {@code scope} carries (their override wins). */
    boolean eligibleWithTools(TenantScope scope, UUID agentId) {
        return behaviour.bool(ELIGIBLE_WITH_TOOLS_KEY, scope, agentId, properties::isEligibleWithTools);
    }

    boolean keyOnConversation() {
        return properties.isKeyOnConversation();
    }

    /**
     * The conversation part of a cache key for a message that follows {@code earlier} (oldest first, as the
     * model is replayed it): {@code ""} when keying on the conversation is off or there is nothing before the
     * message, else a digest of the newest turns.
     */
    public String conversationKey(List<ChatTurn> earlier) {
        return conversationKey(earlier, properties.getContextTurns());
    }

    /**
     * As {@link #conversationKey(List)} with the number of turns {@code agentId} of the tenant {@code scope} carries
     * ({@code chat.cache.contextTurns}). A reader and a writer of the same entry must pass the same agent: the number
     * of turns is part of the key.
     */
    public String conversationKey(List<ChatTurn> earlier, TenantScope scope, UUID agentId) {
        if (!properties.isKeyOnConversation() || earlier == null || earlier.isEmpty()) {
            return "";
        }
        return conversationKey(
                earlier, behaviour.integer(CONTEXT_TURNS_KEY, scope, agentId, properties::getContextTurns));
    }

    private String conversationKey(List<ChatTurn> earlier, int contextTurns) {
        if (!properties.isKeyOnConversation() || earlier == null || earlier.isEmpty()) {
            return "";
        }
        int from = Math.max(0, earlier.size() - Math.max(1, contextTurns));
        StringBuilder text = new StringBuilder();
        for (ChatTurn turn : earlier.subList(from, earlier.size())) {
            text.append(turn.role() == null ? "?" : turn.role().name())
                    .append('\u001f')
                    .append(normalise(turn.content()))
                    .append('\u001e');
        }
        return sha256(text.toString());
    }

    private static String normalise(String content) {
        return content == null ? "" : content.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
