package com.agenticai.core.common.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The only class in this package that composes all three tier strategies and knows
 * all four tiers exist. Constructor-injected, no static/global state.
 *
 * <p><b>Masking guarantee</b>: {@link #explain(FailureContext)}'s literal first
 * statement is masking the incoming context (see {@link #toMasked}) - every strategy
 * below it only ever receives the resulting {@link MaskedFailureContext}, never the
 * raw {@link FailureContext}/{@code Throwable}. There is no branch in this method
 * that can reach a strategy before masking has run.
 */
@Service
public class DefaultFailureExplanationService implements FailureExplanationService {

    private final SensitiveDataMasker masker;
    private final BasicTierStrategy basicTierStrategy;
    private final IntermediateTierStrategy intermediateTierStrategy;
    private final LlmBackedTechnicalStrategy technicalStrategy;

    public DefaultFailureExplanationService(
        SensitiveDataMasker masker,
        BasicTierStrategy basicTierStrategy,
        IntermediateTierStrategy intermediateTierStrategy,
        LlmBackedTechnicalStrategy technicalStrategy
    ) {
        this.masker = masker;
        this.basicTierStrategy = basicTierStrategy;
        this.intermediateTierStrategy = intermediateTierStrategy;
        this.technicalStrategy = technicalStrategy;
    }

    @Override
    public FailureExplanation explain(FailureContext context) {
        MaskedFailureContext masked = toMasked(context);

        String basic = basicTierStrategy.explain(masked);
        String intermediate = intermediateTierStrategy.explain(masked);
        LlmBackedTechnicalStrategy.TechnicalExplanation technicalExplanation = technicalStrategy.explain(masked);

        return new FailureExplanation(
            basic,
            intermediate,
            technicalExplanation.technical(),
            technicalExplanation.expert(),
            technicalExplanation.llmGenerated()
        );
    }

    private MaskedFailureContext toMasked(FailureContext context) {
        Throwable exception = context.exception();
        String exceptionSimpleName = exception != null
            ? exception.getClass().getSimpleName() : "UnknownException";
        String exceptionClassName = exception != null
            ? exception.getClass().getName() : "unknown";
        String rawMessage = rawMessage(exception);

        Map<String, String> maskedAdditionalContext = new LinkedHashMap<>();
        if (context.additionalContext() != null) {
            context.additionalContext().forEach((key, value) ->
                maskedAdditionalContext.put(key, masker.mask(value)));
        }

        return new MaskedFailureContext(
            exceptionSimpleName,
            exceptionClassName,
            masker.mask(rawMessage),
            masker.mask(context.toolOrProviderName()),
            context.issueSourceHint(),
            maskedAdditionalContext,
            context.chatClient()
        );
    }

    private static String rawMessage(Throwable exception) {
        if (exception == null) {
            return null;
        }
        String message = exception.getMessage();
        return message != null ? message : exception.toString();
    }
}
