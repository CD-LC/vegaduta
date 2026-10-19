package com.agenticai.core.common.diagnostics;

import com.agenticai.core.agents.skills.learning.SkillDraftScrubber;
import org.springframework.stereotype.Component;

/**
 * Plugs {@link SensitiveDataMasker} into the skill-learning loop. The loop lives in a module that
 * cannot depend on this one, so it asks for a {@link SkillDraftScrubber} bean and applies it on top
 * of its own built-in scrubbing: stack paths, e-mail addresses, phone numbers, IP addresses and
 * {@code key=value} secrets are masked in the transcript sent to the model and in the draft it
 * returns, before anything is stored.
 */
@Component
class MaskerSkillDraftScrubber implements SkillDraftScrubber {

    private final SensitiveDataMasker masker;

    MaskerSkillDraftScrubber(SensitiveDataMasker masker) {
        this.masker = masker;
    }

    @Override
    public String scrub(String text) {
        return masker.mask(text);
    }
}
