package com.agenticai.core.chat;

import com.agenticai.core.chat.FimCompletionService.FimCompletionResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code POST /api/completions/fim} - one fill-in-the-middle code completion for an IDE client. The
 * thin HTTP shell over {@link FimCompletionService}; all of the policy (entitlements, model pick,
 * prompt shape, deadline, cleanup) lives there.
 *
 * <p><b>Security posture.</b> This route is NOT listed in {@code SecurityConfig}, so it falls
 * through to that chain's terminal {@code anyRequest().authenticated()} - a valid tenant JWT is
 * required, with no role restriction beyond that. That is deliberate and correct for this endpoint
 * (every authenticated member of a tenant may get completions, the same way every member may chat),
 * and it is the same fall-through posture the file's own comments describe for other member-level
 * routes. It is NOT permitAll: an unregistered path in that chain is authenticated, not open. Spend
 * is bounded per tenant and per user by the entitlement gate rather than by a role.
 *
 * <p>Bare DTOs in and out, constructor injection, no {@code ResponseEntity} wrapping - same style
 * as this module's {@code LlmCallLogController}/{@code SpendReportController} siblings. Validation
 * is hand-rolled rather than {@code @Valid}-driven because {@code spring-boot-starter-validation}
 * is not a dependency of this module.
 */
@RestController
@RequestMapping(value = "/api/completions", produces = MediaType.APPLICATION_JSON_VALUE)
public class FimCompletionController {

    /**
     * Hard ceiling on the context an editor may post, across prefix and suffix together. Far above
     * what {@link FimPrompt} actually windows down to ({@code MAX_PREFIX_CHARS} + {@code
     * MAX_SUFFIX_CHARS}) - this is not a quality knob, just a refusal to parse a whole repository
     * into memory on a request that will use a few kilobytes of it.
     */
    static final int MAX_CONTEXT_CHARS = 200_000;

    private final FimCompletionService completionService;

    public FimCompletionController(FimCompletionService completionService) {
        this.completionService = completionService;
    }

    /**
     * @param prefix the file content before the cursor (required unless {@code suffix} is present)
     * @param suffix the file content after the cursor
     * @param language editor language id, a hint only
     * @param maxTokens optional caller ceiling - honoured only downwards, see {@link
     *     FimCompletionService#clampMaxTokens}
     */
    public record FimCompletionRequest(
            String prefix, String suffix, String language, Integer maxTokens) {}

    /**
     * {@code completion} is always present and may be empty - an empty string is the normal "no
     * suggestion" answer, not an error, so a client never has to branch on a status code to decide
     * whether to show something. {@code model} is null whenever {@code completion} is empty.
     */
    public record FimCompletionResponse(String completion, String model) {}

    @PostMapping(value = "/fim", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FimCompletionResponse fim(@RequestBody FimCompletionRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }
        int contextChars = length(request.prefix()) + length(request.suffix());
        if (contextChars == 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "At least one of 'prefix' or 'suffix' is required");
        }
        if (contextChars > MAX_CONTEXT_CHARS) {
            throw new ResponseStatusException(
                    HttpStatus.CONTENT_TOO_LARGE,
                    "prefix + suffix must be at most " + MAX_CONTEXT_CHARS + " characters");
        }

        FimCompletionResult result;
        try {
            result =
                    completionService.complete(
                            request.prefix(),
                            request.suffix(),
                            request.language(),
                            request.maxTokens());
        } catch (FimCompletionService.EntitlementsUnavailableException e) {
            // The entitlement adapter is not registered - the service refuses
            // to serve unmetered inference. 503, not 500: nothing about this
            // request is wrong, the deployment is incomplete. Caught by its
            // own type, not as IllegalStateException: an ISE thrown from
            // inside an adapter or a provider client is a server fault and
            // must not be reported to the client as an incomplete deployment.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
        // Quota/budget rejections deliberately are NOT caught here: they are
        // TenantLimitExceededException (and its PerUserBudgetExhaustedException
        // subclass) and GlobalExceptionHandler already renders them as the
        // platform's standard 403 / 402 "top up to continue" bodies. That
        // handler lives in core/app, which this module does not depend on, so
        // what a test here can pin is the propagation, not the status - see
        // FimCompletionServiceTest's controller tests.
        return new FimCompletionResponse(result.completion(), result.model());
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
