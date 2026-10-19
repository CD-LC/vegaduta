package com.agenticai.core.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * The real "run translation job now" action (#423 - "there should be a job
 * to run and extract all the translations through a central llm, that I
 * will pay for it"). Previously {@link TranslationJobSettings} was purely
 * informational (see its earlier javadoc, still true of everything except
 * this class): nothing in this Spring Boot app could actually make the
 * weekly translation refresh happen sooner than its own cron.
 *
 * <p><b>Why this dispatches a GitHub Action instead of calling an LLM
 * directly:</b> the strings being translated ({@code web/messages/*.json})
 * are static build-time assets of the Next.js app - a DIFFERENT deployable
 * from this Spring Boot service (see {@code translate-messages.mjs}'s own
 * header comment for the identical reasoning). In a real deployment this
 * JVM process has no filesystem access to the Next.js app's source tree at
 * all, so writing translated JSON directly from Java isn't just inelegant,
 * it's often not physically possible. Re-using the existing, already-tested
 * {@code web/scripts/translate-messages.mjs} - which DOES do the actual LLM
 * call, using its own {@code TRANSLATE_LLM_API_KEY} repository secret - via
 * GitHub's {@code workflow_dispatch} REST API is the honest way to give this
 * console a "run it now" button without duplicating that LLM-calling logic
 * in a second language and hoping the two stay in sync.
 *
 * <p>Requires two platform-level (not per-tenant) configuration values,
 * intentionally NOT stored in the database (a secret with push access to
 * this repository's Actions belongs in server config/secrets management, not
 * a row a tenant-facing bug could ever expose):
 * <ul>
 *   <li>{@code GITHUB_REPO} (env/property {@code app.translation-job.github-repo}) -
 *       {@code "owner/repo"}.</li>
 *   <li>{@code GITHUB_ACTIONS_TOKEN} (env/property {@code
 *       app.translation-job.github-token}) - a GitHub PAT (classic scope
 *       {@code repo} + {@code workflow}, or a fine-grained token with
 *       "Actions: write" on this repo).</li>
 * </ul>
 * Neither is set by default; {@link #triggerNow} returns a clear
 * NOT_CONFIGURED result (never a 500) until an operator sets both.
 */
@Service
public class TranslationRunService {

    private static final Logger log = LoggerFactory.getLogger(TranslationRunService.class);
    private static final String DEFAULT_WORKFLOW_FILE = "translate-messages.yml";
    private static final String DEFAULT_REF = "main";

    private final TranslationJobSettingsService settingsService;
    private final String githubRepo;
    private final String githubToken;
    private final String githubApiBase;
    private final RestClient restClient;

    /**
     * @param restClientBuilder the AUTO-CONFIGURED builder, injected rather
     *     than {@code RestClient.create()} (2026-07-29 security audit).
     *     {@code create()} bypasses Spring Boot's HTTP client
     *     auto-configuration entirely, so this client had no connect and no
     *     read timeout - and {@link #triggerNow} runs inside a
     *     {@code @Transactional} method, so a hung api.github.com held a
     *     database transaction open for as long as it hung. Injecting the
     *     shared builder picks up {@code spring.http.client.*}.
     */
    public TranslationRunService(
        TranslationJobSettingsService settingsService,
        RestClient.Builder restClientBuilder,
        @Value("${app.translation-job.github-repo:}") String githubRepo,
        @Value("${app.translation-job.github-token:}") String githubToken,
        @Value("${app.translation-job.github-api-base:https://api.github.com}") String githubApiBase
    ) {
        this.settingsService = settingsService;
        this.githubRepo = githubRepo;
        this.githubToken = githubToken;
        this.githubApiBase = githubApiBase;
        this.restClient = restClientBuilder.build();
    }

    /**
     * Dispatches {@code .github/workflows/translate-messages.yml} on branch
     * {@code main} right now, instead of waiting for its Monday cron. Always
     * records the outcome on the singleton {@link TranslationJobSettings}
     * row (via {@link TranslationJobSettings#recordTrigger}) so the
     * Ecosystem Maintenance console can show "last run: ... - status" even
     * when the dispatch itself failed.
     */
    @Transactional
    public TranslationJobSettings triggerNow(String triggeredBy) {
        TranslationJobSettings settings = settingsService.get();

        if (githubRepo == null || githubRepo.isBlank() || githubToken == null || githubToken.isBlank()) {
            log.warn("Translation job trigger requested but GITHUB_REPO/GITHUB_ACTIONS_TOKEN aren't configured.");
            settings.recordTrigger(
                triggeredBy, "NOT_CONFIGURED",
                "This deployment hasn't configured app.translation-job.github-repo / github-token - set both "
                    + "(env vars GITHUB_REPO / GITHUB_ACTIONS_TOKEN) to enable on-demand runs. The weekly "
                    + "scheduled job is unaffected."
            );
            return settings;
        }

        String workflowFile = settings.getWorkflowPathHint() != null && !settings.getWorkflowPathHint().isBlank()
            ? settings.getWorkflowPathHint().substring(settings.getWorkflowPathHint().lastIndexOf('/') + 1)
            : DEFAULT_WORKFLOW_FILE;
        String url = githubApiBase + "/repos/" + githubRepo + "/actions/workflows/" + workflowFile + "/dispatches";

        try {
            restClient.post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + githubToken)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"ref\":\"" + DEFAULT_REF + "\"}")
                .retrieve()
                .toBodilessEntity();
            log.info("Dispatched {} on {} (requested by {})", workflowFile, DEFAULT_REF, triggeredBy);
            settings.recordTrigger(
                triggeredBy, "DISPATCHED",
                "GitHub Actions run requested - check the Actions tab for progress; it opens a PR with the "
                    + "updated translation files rather than pushing directly to main."
            );
        } catch (RestClientResponseException e) {
            // Status + body size only - the upstream body can echo request content (JAVA-033).
            log.error("Translation job dispatch failed: {} (body {})", e.getStatusCode(),
                com.agenticai.core.security.LogMasking.size(e.getResponseBodyAsString()));
            String message = e.getStatusCode() == HttpStatus.NOT_FOUND
                ? "GitHub returned 404 - check app.translation-job.github-repo and that the workflow file name matches."
                : e.getStatusCode() == HttpStatus.UNAUTHORIZED || e.getStatusCode() == HttpStatus.FORBIDDEN
                    ? "GitHub rejected the token (401/403) - it needs 'repo' + 'workflow' scope (classic PAT) or "
                        + "Actions:write (fine-grained token) on this repository."
                    : "GitHub Actions dispatch failed: HTTP " + e.getStatusCode() + " - " + e.getResponseBodyAsString();
            settings.recordTrigger(triggeredBy, "FAILED", message);
        } catch (Exception e) {
            log.error("Translation job dispatch failed unexpectedly", e);
            settings.recordTrigger(triggeredBy, "FAILED", "Unexpected error: " + e.getMessage());
        }
        return settings;
    }
}
