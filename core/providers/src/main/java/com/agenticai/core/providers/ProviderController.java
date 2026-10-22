package com.agenticai.core.providers;

import com.agenticai.core.providers.dto.ModalityCatalogResponse;
import com.agenticai.core.providers.dto.ProviderActivationRequest;
import com.agenticai.core.providers.dto.ProviderAvailabilityResponse;
import com.agenticai.core.providers.dto.ProviderResponse;
import com.agenticai.core.providers.modality.ModalityCatalogService;
import com.agenticai.core.tenancy.ListPaging;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.exc.InvalidFormatException;

@RestController
@RequestMapping("/api/providers")
public class ProviderController {

    private final ProviderService providerService;
    private final ProviderModelListingService modelListingService;
    private final PlatformHostedLlmProviderService platformHostedLlmProviderService;
    private final ModalityCatalogService modalityCatalogService;

    public ProviderController(
            ProviderService providerService,
            ProviderModelListingService modelListingService,
            PlatformHostedLlmProviderService platformHostedLlmProviderService,
            ModalityCatalogService modalityCatalogService) {
        this.providerService = providerService;
        this.modelListingService = modelListingService;
        this.platformHostedLlmProviderService = platformHostedLlmProviderService;
        this.modalityCatalogService = modalityCatalogService;
    }

    @PostMapping
    public ResponseEntity<ProviderResponse> activate(
            @Valid @RequestBody ProviderActivationRequest request) {
        ProviderResponse response = providerService.activate(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<ProviderResponse>> list(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long offset) {
        return ListPaging.window(providerService.listAll(), limit, offset, 100, 500);
    }

    /**
     * "Give option to edit, delete the added key" - direct feedback. Same three-endpoint
     * (activate/list/deactivate) shape SarvamController/E2bCredentialController already use.
     */
    @DeleteMapping("/{provider}")
    public ResponseEntity<Void> deactivate(@PathVariable String provider) {
        ProviderName providerName = parseProvider(provider);
        providerService.deactivate(providerName);
        return ResponseEntity.noContent().build();
    }

    /**
     * VEGADUTA-MASTER-ROADMAP-2026-07-19.md §4.4 - the correct signal for "can this tenant
     * create/chat with an agent," which {@link #list} (BYOK only) is not. See {@link
     * ProviderAvailabilityResponse}'s javadoc. {@code web/}'s {@code AgentWorkbench.tsx}
     * create-gate should read {@code canCreateAgents} from here instead of {@code list().length >
     * 0}.
     */
    @GetMapping("/availability")
    public ProviderAvailabilityResponse availability() {
        return providerService.checkAvailability();
    }

    /**
     * Server-generated STT/TTS/video provider catalog - the replacement for the workbench's
     * hardcoded provider arrays and its per-brand availability probes. Every enum constant becomes
     * one row; availability (credential present, sidecar healthy, implementation registered) is
     * resolved here by the modality factories, so a new provider appears in the selectors with no
     * frontend change. See {@link ModalityCatalogService}'s javadoc.
     */
    @GetMapping("/modalities")
    public ModalityCatalogResponse modalities() {
        return modalityCatalogService.catalog();
    }

    /**
     * Backs the model dropdown in AgentsPanel.js - "in model selection also, there should be a
     * dropdown which lists all available models in that provider" (direct feedback). Now served
     * through {@link ProviderModelListingService}: when this tenant has an active BYOK key for the
     * provider (or the provider is OpenRouter, whose list is public), the list is fetched live with
     * that key and enriched from the cached catalog; otherwise - and on any live-fetch failure -
     * the response is the cached catalog exactly as before (see {@link
     * ProviderModelCatalogService}'s javadoc for how that cache is kept fresh). Response shape is
     * unchanged apart from the appended description/deprecated/source fields.
     */
    @GetMapping("/{provider}/models")
    public ResponseEntity<List<ProviderModelResponse>> listModels(
            @PathVariable String provider,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long offset) {
        ProviderName providerName = parseProvider(provider);
        return ListPaging.window(
                modelListingService.listModels(providerName), limit, offset, 200, 1000);
    }

    /**
     * "application should show what platform llm is configured" - direct feedback. Tenant-facing
     * (any authenticated tenant, not just FREE or platform-admins) read of the actual provider
     * label/model backing the platform-hosted pool - the value {@link
     * com.agenticai.core.providers.dto.ProviderAvailabilityResponse#platformHostedAvailable} only
     * signals as a boolean today. Backs the Providers page's FREE-tier state and the Agent
     * Workbench's platform-LLM badge.
     */
    @GetMapping("/platform-llm")
    public PlatformHostedLlmActiveResponse platformLlm() {
        return platformHostedLlmProviderService.getActiveSummary();
    }

    /**
     * Resolves the {@code {provider}} path segment. An unknown name is a 400 that lists the allowed
     * values, instead of the bare {@code IllegalArgumentException} from {@code Enum#valueOf}.
     */
    static ProviderName parseProvider(String provider) {
        try {
            return ProviderName.valueOf(provider.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, unknownProviderMessage());
        }
    }

    static String unknownProviderMessage() {
        return "Unknown provider. providerName must be one of "
                + Arrays.toString(ProviderName.values())
                + ".";
    }

    /**
     * {@code POST /api/providers} with an unknown {@code providerName} fails in Jackson before
     * validation runs. Answer that one case with the allowed values; any other unreadable body
     * keeps the generic message the global handler uses.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException e) {
        boolean badProviderName = false;
        for (Throwable t = e; t != null; t = (t.getCause() == t ? null : t.getCause())) {
            if (t instanceof InvalidFormatException ife
                    && ProviderName.class.equals(ife.getTargetType())) {
                badProviderName = true;
                break;
            }
        }
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST,
                        badProviderName
                                ? unknownProviderMessage()
                                : "The request body is missing or not valid JSON for this endpoint.");
        problem.setType(
                URI.create(
                        "urn:vegaduta:problem:"
                                + (badProviderName
                                        ? "unknown-provider-name"
                                        : "malformed-request-body")));
        return ResponseEntity.badRequest().body(problem);
    }
}
