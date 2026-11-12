package com.agenticai.core.agents.memory;

import org.springframework.ai.chat.client.ChatClient;

/**
 * Narrow port: a {@link ChatClient} for background fact-to-graph extraction on the current tenant's
 * thread. It exists so the memory approve path can run {@code EntityExtractionService} without
 * reaching the turn's package-private client resolver; the adapter is {@code AssistService}, which
 * already resolves the hosted pool vs. the workspace's own provider (and skips a provider key that
 * was refused recently, since no person waits on this call).
 */
public interface MemoryExtractionChatClients {

    /**
     * A client for one low-priority extraction call. Throws when the workspace has no usable
     * provider; callers treat that as "skip the graph write", never as a failed approval.
     */
    ChatClient forBackgroundExtraction();
}
