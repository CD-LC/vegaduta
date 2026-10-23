package com.agenticai.core.agents.settings;

import com.agenticai.core.agents.memory.MemoryLimitsProperties;
import com.agenticai.core.agents.turn.ChatLimitsProperties;
import com.agenticai.core.agents.turn.FollowUpProperties;
import com.agenticai.core.agents.turn.ToolLedgerProperties;
import com.agenticai.core.agents.turn.cache.SemanticCacheProperties;

/** The product-controls catalog over the property objects a test hands to the runtime, behind a real resolver and store. */
public final class ProductControlsFixture {

    public final SemanticCacheProperties semanticCache = new SemanticCacheProperties();
    public final ChatLimitsProperties limits = new ChatLimitsProperties();
    public final FollowUpProperties followUp = new FollowUpProperties();
    public final MemoryLimitsProperties memory = new MemoryLimitsProperties();
    public final ToolLedgerProperties ledger = new ToolLedgerProperties();
    private SettingsRig rig;

    public ProductControlsCatalog catalog() {
        return new ProductControlsCatalog(semanticCache, limits, followUp, memory, ledger);
    }

    /** The rig over the properties as they are NOW: call after tuning them. */
    public synchronized SettingsRig rig() {
        if (rig == null) {
            rig = new SettingsRig(catalog());
        }
        return rig;
    }
}
