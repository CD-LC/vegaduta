package com.agenticai.core.agents.learning;

import com.agenticai.core.agents.memory.MemoryBlocks;
import com.agenticai.core.agents.memory.TenantMemoryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Approved PREFERENCE: appended as one bullet to the person's {@code user_profile} memory block (the block every
 * turn already carries), within the block's character limit. Text already in the block is not added twice.
 */
@Component
@ConditionalOnProperty(name = "app.learning.proposals.enabled", havingValue = "true")
public class PreferenceApplier implements LearningProposalApplier {

    private final ObjectProvider<TenantMemoryService> memory;

    public PreferenceApplier(ObjectProvider<TenantMemoryService> memory) {
        this.memory = memory;
    }

    @Override
    public LearningProposalKind kind() {
        return LearningProposalKind.PREFERENCE;
    }

    @Override
    public void apply(LearningProposal proposal, String text) throws LearningProposalApplyException {
        TenantMemoryService service = memory.getIfAvailable();
        if (service == null || !service.blocksEnabled()) {
            throw new LearningProposalApplyException("Memory blocks are not available on this install.");
        }
        String bullet = "- " + text.strip().replaceAll("\\s+", " ");
        String subject = proposal.getSubject();
        String existing =
                service.listBlocks(subject).stream()
                        .filter(b -> MemoryBlocks.USER_PROFILE.equals(b.label()))
                        .map(TenantMemoryService.MemoryBlockEntry::content)
                        .findFirst()
                        .orElse("");
        if (existing.contains(bullet)) {
            return;
        }
        String updated = existing.isBlank() ? bullet : existing.stripTrailing() + "\n" + bullet;
        if (updated.length() > service.maxCharsPerBlock()) {
            throw new LearningProposalApplyException(
                    "Your profile is full (" + service.maxCharsPerBlock()
                            + " characters). Shorten it in Memory, then approve this again.");
        }
        try {
            service.putBlock(subject, MemoryBlocks.USER_PROFILE, updated);
        } catch (IllegalArgumentException e) {
            throw new LearningProposalApplyException(e.getMessage());
        }
    }
}
