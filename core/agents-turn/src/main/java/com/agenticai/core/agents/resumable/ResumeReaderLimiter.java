package com.agenticai.core.agents.resumable;

import java.util.HashMap;
import java.util.Map;

/**
 * Caps concurrently open resume readers per tenant+user (a reconnect storm after a network blip).
 * A cap of 0 or less is unlimited.
 */
final class ResumeReaderLimiter {

    private final int maxReadersPerOwner;
    private final Map<String, Integer> readersByOwner = new HashMap<>();

    ResumeReaderLimiter(int maxReadersPerOwner) {
        this.maxReadersPerOwner = maxReadersPerOwner;
    }

    synchronized boolean acquire(String ownerKey) {
        int open = readersByOwner.getOrDefault(ownerKey, 0);
        if (maxReadersPerOwner > 0 && open >= maxReadersPerOwner) {
            return false;
        }
        readersByOwner.put(ownerKey, open + 1);
        return true;
    }

    synchronized void release(String ownerKey) {
        int open = readersByOwner.getOrDefault(ownerKey, 0) - 1;
        if (open <= 0) {
            readersByOwner.remove(ownerKey);
        } else {
            readersByOwner.put(ownerKey, open);
        }
    }
}
