package com.agenticai.core.providers;

import com.agenticai.core.crypto.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Backs {@code POST /api/providers/local-openai/probe}: measures how a model on the tenant's own
 * {@link ProviderName#LOCAL_OPENAI} server makes tool calls, with the same {@link
 * ToolCallCapabilityProbe} the hosted pool runs on save (one canned two-tool request, graded
 * {@link ToolCallGrade#STRUCTURED} / {@link ToolCallGrade#TEXT} / {@link ToolCallGrade#NONE}), and
 * reports the server kind it detected.
 *
 * <p>Advisory only: the answer tells the tenant whether to expect reliable agent tool use from this
 * model and how to fix a server that is not set up for it. It changes no stored state and does not
 * alter how chat turns run. A small local model that never calls tools is the most common reason an
 * agent "does nothing" on a laptop; this makes that visible before the first chat.
 *
 * <p>The probe goes through the guarded transport ({@link ToolCallCapabilityProbe#probeTenantServer}):
 * the base URL is tenant-supplied. The SSRF host check runs first via {@link
 * LocalOpenAiServerClient#requireAllowed}. The probe is optional ({@code core.chat} provides it); a
 * context without it, or with {@code app.llm.tool-call-probe.enabled=false}, answers "not measured".
 */
@Service
public class LocalOpenAiProbeService {

    private static final Logger log = LoggerFactory.getLogger(LocalOpenAiProbeService.class);

    private final LlmProviderCredentialRepository credentialRepository;
    private final EncryptionService encryptionService;
    private final LocalOpenAiServerClient serverClient;
    private final ObjectProvider<ToolCallCapabilityProbe> probe;

    public LocalOpenAiProbeService(
        LlmProviderCredentialRepository credentialRepository,
        EncryptionService encryptionService,
        LocalOpenAiServerClient serverClient,
        ObjectProvider<ToolCallCapabilityProbe> probe
    ) {
        this.credentialRepository = credentialRepository;
        this.encryptionService = encryptionService;
        this.serverClient = serverClient;
        this.probe = probe;
    }

    /**
     * Not {@code @Transactional}: the probe is a slow HTTP call and must not pin a pooled connection.
     *
     * @throws ProviderNotActiveException when this tenant has no active LOCAL_OPENAI connection
     */
    public LocalOpenAiProbeResponse probe(String model) {
        LlmProviderCredential stored = credentialRepository.findByProviderName(ProviderName.LOCAL_OPENAI)
            .filter(c -> c.getStatus() == ProviderStatus.ACTIVE)
            .orElseThrow(() -> new ProviderNotActiveException(ProviderName.LOCAL_OPENAI));
        LocalOpenAiCredential credential =
            LocalOpenAiCredential.decode(encryptionService.decrypt(stored.getEncryptedApiKey()));
        serverClient.requireAllowed(credential);

        ToolCallCapabilityProbe measurer = probe.getIfAvailable();
        if (measurer == null) {
            return LocalOpenAiProbeResponse.notMeasured(model);
        }
        try {
            return measurer.probeTenantServer(
                    credential.apiRoot(), null, model, credential.hasApiKey() ? credential.apiKey() : null)
                .map(result -> LocalOpenAiProbeResponse.of(model, result))
                .orElseGet(() -> LocalOpenAiProbeResponse.notMeasured(model));
        } catch (RuntimeException e) {
            log.warn("Tool-call probe of local model '{}' failed: {}", model, e.toString());
            return LocalOpenAiProbeResponse.notMeasured(model);
        }
    }
}
