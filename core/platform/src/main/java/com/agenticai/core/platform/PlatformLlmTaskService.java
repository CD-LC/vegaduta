package com.agenticai.core.platform;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.providers.ProviderName;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Backs {@code PlatformController}'s {@code GET/PUT /api/platform/llm-tasks}
 * endpoints - see {@link PlatformTask}'s javadoc for why this is a small,
 * fixed, strictly-internal task registry rather than a general "default
 * provider" feature.
 */
@Service
public class PlatformLlmTaskService {

    private final PlatformLlmTaskConfigRepository repository;
    private final EncryptionService encryptionService;

    public PlatformLlmTaskService(PlatformLlmTaskConfigRepository repository, EncryptionService encryptionService) {
        this.repository = repository;
        this.encryptionService = encryptionService;
    }

    /**
     * Every {@link PlatformTask} value, always - a task with no stored row
     * yet is represented as "unconfigured" in the list, never a 404 for
     * that one entry.
     */
    public List<PlatformLlmTaskConfigResponse> list() {
        return java.util.Arrays.stream(PlatformTask.values())
            .map(task -> repository.findByTask(task)
                .map(PlatformLlmTaskConfigResponse::from)
                .orElseGet(() -> PlatformLlmTaskConfigResponse.unconfigured(task)))
            .toList();
    }

    /**
     * Creates or updates the routing config for one {@link PlatformTask}.
     * {@code plaintextApiKeyOrNull} is only encrypted and stored when it has
     * real text ({@link StringUtils#hasText(String)}, the same check {@code
     * EncryptionService}'s own callers already use elsewhere in this
     * codebase) - a blank/absent value leaves any already-stored key
     * untouched rather than wiping it on an unrelated provider/model edit.
     *
     * <p>{@code providerName}/{@code model} are both required and validated here.
     * Both back {@code NOT NULL} columns on {@link PlatformLlmTaskConfig} and
     * nothing checked them before, so a request that simply omitted {@code model}
     * (or sent a whitespace-only one) reached the DB and came back as a {@code
     * DataIntegrityViolationException} - which has no handler in {@code
     * GlobalExceptionHandler} and therefore surfaced to the admin as an opaque
     * 500 "Something went wrong", plus a misleading Issues-portal row. Plain
     * {@link IllegalArgumentException} rather than a new exception type: that
     * handler already maps to 400 and exists precisely for "validation that
     * doesn't warrant its own named exception type" (see its comment there).
     * {@code model} arrives already trimmed - see {@link
     * PlatformLlmTaskUpsertRequest}'s compact constructor.
     */
    public PlatformLlmTaskConfigResponse upsert(
        PlatformTask task, ProviderName providerName, String model, String plaintextApiKeyOrNull
    ) {
        if (providerName == null) {
            throw new IllegalArgumentException("providerName is required to configure the " + task + " task.");
        }
        if (!StringUtils.hasText(model)) {
            throw new IllegalArgumentException(
                "A model id is required to configure the " + task + " task - pick one from the "
                    + providerName + " model list.");
        }

        String newEncryptedApiKeyOrNull = StringUtils.hasText(plaintextApiKeyOrNull)
            ? encryptionService.encrypt(plaintextApiKeyOrNull)
            : null;

        PlatformLlmTaskConfig config = repository.findByTask(task)
            .map(existing -> {
                existing.apply(providerName, model, newEncryptedApiKeyOrNull);
                return existing;
            })
            .orElseGet(() -> new PlatformLlmTaskConfig(task, providerName, model, newEncryptedApiKeyOrNull));

        return PlatformLlmTaskConfigResponse.from(repository.save(config));
    }
}
