package com.agenticai.core.chat;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only per-model $/MTok pricing for the web model picker's cost
 * transparency badge (Chatbase-style "this model costs roughly Nx more than
 * that one" comparison) - direct feedback captured under the 2026-09-23
 * gap-radar fan-out's P0 item "surface per-model cost transparency."
 *
 * <p>Purely a read surface over {@link LlmPricingCatalog}'s already
 * hand-maintained rate table - this controller adds no pricing logic of its
 * own, it just serves the same $/MTok rates {@link LlmPricingCatalog}
 * already uses to price and budget real calls.
 *
 * <p>Deliberately NOT under {@code /api/admin/**} and carries no plan gate,
 * unlike {@link LlmCallLogController}/{@link AgentSessionLlmUsageController}:
 * those two serve this TENANT's actual incurred spend (real usage data,
 * PREMIUM+ per this product's billing posture), while this endpoint serves
 * static list-price reference data - the same kind of thing a public pricing
 * page shows - so a FREE-tier tenant picking a model to decide whether to
 * upgrade needs to see it too, same as every other authenticated read under
 * this product's default {@code anyRequest().authenticated()} posture (see
 * {@code SecurityConfig}).
 */
@RestController
@RequestMapping("/api/model-pricing")
public class ModelPricingController {

    private final LlmPricingCatalog pricingCatalog;

    public ModelPricingController(LlmPricingCatalog pricingCatalog) {
        this.pricingCatalog = pricingCatalog;
    }

    /**
     * @param modelIds comma-separated model ids, e.g. {@code modelIds=gpt-4o,claude-opus-4-8}
     *     (matches exactly the {@code modelId} values {@code
     *     GET /api/providers/{provider}/models} already returns - the web
     *     picker passes the ids of whatever provider's dropdown it just
     *     rendered). Blank/whitespace-only entries are dropped; an id the
     *     catalog doesn't recognize still comes back (at the conservative
     *     default rate - see {@link LlmPricingCatalog#lookupRatesPerMillionTokens})
     *     rather than being silently omitted, so the frontend never has to
     *     treat "missing from the response" as its own case.
     */
    @GetMapping
    public List<ModelPriceEntry> pricing(@RequestParam String modelIds) {
        return price(Arrays.asList(modelIds.split(",")));
    }

    /**
     * Same lookup, ids in a JSON body: {@code {"modelIds":["gpt-4o", ...]}}.
     * The GET form broke in prod (2026-09-24) the moment a provider's catalog
     * grew to ~500 models - the query string alone was ~20 KB, past Tomcat's
     * 8 KB request-line/header limit, so the edge answered with a bare error
     * (no CORS headers) and the browser reported the whole API as
     * unreachable. The web picker now always POSTs.
     */
    @PostMapping
    public List<ModelPriceEntry> pricing(@RequestBody ModelPricingRequest request) {
        return price(request == null || request.modelIds() == null ? List.of() : request.modelIds());
    }

    private List<ModelPriceEntry> price(List<String> ids) {
        return ids.stream()
            .filter(id -> id != null)
            .map(String::trim)
            .filter(id -> !id.isEmpty())
            .distinct()
            .map(id -> {
                LlmPricingCatalog.RatesPerMillionTokens rates = pricingCatalog.lookupRatesPerMillionTokens(id);
                return new ModelPriceEntry(id, rates.inputPerMillionUsd(), rates.outputPerMillionUsd());
            })
            .toList();
    }

    /** Body of {@code POST /api/model-pricing}. */
    public record ModelPricingRequest(List<String> modelIds) {}

    /** One model's estimated $/MTok input/output rate - see {@link LlmPricingCatalog}'s "estimate, not a bill" caveat. */
    public record ModelPriceEntry(String modelId, BigDecimal inputPerMillionUsd, BigDecimal outputPerMillionUsd) {}
}
