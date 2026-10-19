package com.agenticai.core.platform;

import com.agenticai.core.providers.ProviderName;
import org.springframework.stereotype.Service;

/**
 * Backs {@code PlatformController}'s {@code GET/PUT /api/platform/translation-job}
 * endpoints. See {@link TranslationJobSettings}'s javadoc for the informational-
 * only scope - this service never calls out to GitHub in any way.
 */
@Service
public class TranslationJobSettingsService {

    /**
     * Matches {@code .github/workflows/translate-messages.yml}'s real {@code
     * cron: "0 6 * * 1"} - a sensible human-readable default for the first
     * time this singleton row is created, not independently chosen.
     */
    private static final String DEFAULT_CRON_DISPLAY = "0 6 * * 1 (every Monday, 06:00 UTC)";
    private static final String DEFAULT_WORKFLOW_PATH_HINT = ".github/workflows/translate-messages.yml";
    private static final ProviderName DEFAULT_PROVIDER = ProviderName.ANTHROPIC;
    private static final String DEFAULT_MODEL = "claude-3-5-sonnet-latest";

    private final TranslationJobSettingsRepository repository;

    public TranslationJobSettingsService(TranslationJobSettingsRepository repository) {
        this.repository = repository;
    }

    /** Never throws 404 - creates a sensible default singleton row on first call. */
    public TranslationJobSettings get() {
        return repository.findById(TranslationJobSettings.SINGLETON_ID)
            .orElseGet(this::createDefault);
    }

    /** Loads-or-creates the singleton row, then applies only the supplied (non-null) fields. */
    public TranslationJobSettings update(
        String cronScheduleDisplay, ProviderName providerName, String model,
        String workflowPathHint, String notes, String updatedBy
    ) {
        TranslationJobSettings settings = get();
        settings.applyUpdate(cronScheduleDisplay, providerName, model, workflowPathHint, notes, updatedBy);
        return repository.save(settings);
    }

    private TranslationJobSettings createDefault() {
        TranslationJobSettings defaults = new TranslationJobSettings(
            DEFAULT_CRON_DISPLAY, DEFAULT_PROVIDER, DEFAULT_MODEL, DEFAULT_WORKFLOW_PATH_HINT, null
        );
        return repository.save(defaults);
    }
}
