package com.agenticai.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.attestation.AgentAttestationService;
import com.agenticai.core.agents.tools.web.HttpRequestExecutor;
import com.agenticai.core.agents.tools.x402.X402PaymentService;
import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.secrets.SecretRedactor;
import com.agenticai.core.secrets.SecretReferenceResolver;
import com.agenticai.core.secrets.SecretVaultService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * SEC-007: untrusted run input (webhook / share callers) substituted into an HTTP node's templates
 * must never be able to introduce a vault reference that the executor then resolves and sends to
 * the node's third party.
 *
 * <p>Run input is substituted by {@link WorkflowExpressionResolver} before the handler sees the
 * node (the handler substitutes nothing itself), so these tests drive the real resolver -> handler
 * -> executor path. The resolver defuses data-borne references by splitting the braces with a
 * zero-width space; {@link #sent} strips it so the assertions read as the plain text a person would
 * see.
 */
class WorkflowHttpNodeSecretInjectionTest {

    private static final String SECRET = "sk_live_STRIPE_987654";

    private final List<String> sentBodies = new ArrayList<>();
    private final List<Map<String, String>> sentHeaders = new ArrayList<>();
    private WorkflowHttpMediaNodeHandler handler;
    private final WorkflowExpressionResolver resolver =
            WorkflowExpressionResolver.standalone(new ObjectMapper());
    private static final WorkflowExpressionResolver.NodeEvalInfo INFO =
            new WorkflowExpressionResolver.NodeEvalInfo(
                    java.util.UUID.randomUUID().toString(),
                    "wf",
                    java.util.UUID.randomUUID().toString(),
                    "system:workflow-webhook:ab12");

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @BeforeEach
    void setUp() {
        SecretVaultService vault = mock(SecretVaultService.class);
        when(vault.resolveForDestination(eq("STRIPE_KEY"), any(), eq("api.partner.example")))
                .thenReturn(Optional.of(SECRET));
        when(vault.resolveForRun(anyString(), any())).thenReturn(Optional.of(SECRET));
        HttpRequestExecutor executor =
                new HttpRequestExecutor(
                        provider(new SecretReferenceResolver(vault)),
                                provider(new SecretRedactor()),
                        provider((AgentAttestationService) null),
                                provider((X402PaymentService) null)) {
                    @Override
                    protected RawResponse send(
                            URI uri,
                            String method,
                            Map<String, String> headers,
                            String body,
                            Duration timeout) {
                        sentBodies.add(body);
                        sentHeaders.add(new LinkedHashMap<>(headers));
                        return new RawResponse(
                                200,
                                Map.of("content-type", List.of("application/json")),
                                "{\"ok\":true}".getBytes(StandardCharsets.UTF_8),
                                false);
                    }
                };
        handler =
                new WorkflowHttpMediaNodeHandler(
                        provider(executor), provider((SidecarClient) null), new ObjectMapper());
        // Anonymous webhook caller.
        TenantContext.set(TenantContext.of("tenant-a", "system:workflow-webhook:ab12", Set.of()));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private NodeExecutionResult run(String body, String input) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("method", "POST");
        // Numeric host so the SSRF guard's DNS lookup stays offline: 93.184.216.34 is public.
        config.put("url", "https://93.184.216.34/hook");
        config.put("bodyType", "text");
        config.put("body", body);
        config.put("headers", List.of(Map.of("name", "X-Note", "value", "{{note}}")));
        WorkflowNodeDto node =
                new WorkflowNodeDto(
                        "n1", "http", Map.of(), Map.of("config", config, "label", "HTTP"));
        return handler.executeHttpNode(resolver.resolve(node, input, INFO, null), input);
    }

    /** The body actually sent, with the resolver's zero-width defusing characters removed. */
    private String sent(int i) {
        return sentBodies.get(i).replace("\u200B", "");
    }

    @Test
    void inputFieldCarryingDotReference_isSentLiterally() {
        run(
                "msg={{msg}}",
                "{\"msg\":\"{{secret.STRIPE_KEY}}\",\"note\":\"{{secret:stripe_key}}\"}");
        assertThat(sentBodies).hasSize(1);
        assertThat(sent(0)).isEqualTo("msg={{secret.STRIPE_KEY}}");
        assertThat(sentBodies.get(0)).doesNotContain(SECRET);
        assertThat(sentHeaders.get(0).get("X-Note").replace("\u200B", ""))
                .isEqualTo("{{secret:stripe_key}}");
        assertThat(sentHeaders.get(0).get("X-Note")).doesNotContain(SECRET);
    }

    @Test
    void wholeInputCarryingNestedReference_isSentLiterally() {
        run("{{input}}", "{{{{secret.STRIPE_KEY}}}}");
        assertThat(sentBodies).hasSize(1);
        assertThat(sent(0)).isEqualTo("{{{{secret.STRIPE_KEY}}}}");
        assertThat(sentBodies.get(0)).doesNotContain(SECRET);
    }

    @Test
    void referenceSplitAcrossTwoInputs_isNeverAssembled() {
        run("{{a}}{{b}}", "{\"a\":\"{{sec\",\"b\":\"ret.STRIPE_KEY}}\"}");
        assertThat(sentBodies).hasSize(1);
        assertThat(sent(0)).isEqualTo("{{secret.STRIPE_KEY}}");
        assertThat(sentBodies.get(0)).doesNotContain(SECRET);
    }

    @Test
    void inputValueContainingAnotherPlaceholder_isNotExpandedAgain() {
        // Old sequential replace() would expand {{b}} inside a's value.
        run("{{a}}", "{\"a\":\"{{b}}\",\"b\":\"{{secret.STRIPE_KEY}}\"}");
        assertThat(sentBodies).containsExactly("{{b}}");
        assertThat(sentBodies.get(0)).doesNotContain(SECRET);
    }

    @Test
    void journaledPostCarriesTheStableIdempotencyKeyHeader() {
        EffectIdempotency.bind("run-1:n1:3");
        try {
            run("x", "{}");
        } finally {
            EffectIdempotency.clear();
        }
        assertThat(sentHeaders.get(0)).containsEntry("Idempotency-Key", "run-1:n1:3");
    }

    @Test
    void anAuthorSetIdempotencyKeyWinsAndNoJournalMeansNoHeader() {
        run("x", "{}");
        assertThat(sentHeaders.get(0)).doesNotContainKey("Idempotency-Key");

        EffectIdempotency.bind("run-1:n1:-");
        try {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("method", "POST");
            config.put("url", "https://93.184.216.34/hook");
            config.put("headers", List.of(Map.of("name", "idempotency-key", "value", "mine")));
            WorkflowNodeDto node =
                    new WorkflowNodeDto(
                            "n1", "http", Map.of(), Map.of("config", config, "label", "HTTP"));
            handler.executeHttpNode(node, "{}");
        } finally {
            EffectIdempotency.clear();
        }
        assertThat(sentHeaders.get(1)).containsEntry("idempotency-key", "mine");
        assertThat(sentHeaders.get(1)).doesNotContainKey("Idempotency-Key");
    }
}
