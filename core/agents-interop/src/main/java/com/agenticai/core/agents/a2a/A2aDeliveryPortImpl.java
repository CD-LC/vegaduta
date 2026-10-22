package com.agenticai.core.agents.a2a;

import com.agenticai.core.agents.tools.messaging.A2aDeliveryPort;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import org.springframework.stereotype.Service;

/**
 * agents-interop side of {@link A2aDeliveryPort} - every send goes through {@link
 * A2aClientService#send}, so the operator allow-list, SSRF guard and signed-card verification apply
 * to scheduled-task delivery exactly as they do to the {@code a2a_call_agent} tool.
 */
@ConditionalOnEditionFeature(EditionFeature.A2A_DIRECTORY)
@Service
public class A2aDeliveryPortImpl implements A2aDeliveryPort {

    private final A2aClientService client;

    public A2aDeliveryPortImpl(A2aClientService client) {
        this.client = client;
    }

    @Override
    public A2aSendResult send(String cardUrl, String message, String requestedBy) {
        A2aClientService.Result result = client.send(cardUrl, message);
        String state = result.state() == null ? "unknown" : result.state();
        String detail =
                result.success()
                        ? "remote task "
                                + state
                                + (result.text() == null || result.text().isBlank()
                                        ? ""
                                        : ": " + result.text())
                        : "remote task "
                                + state
                                + (result.text() == null || result.text().isBlank()
                                        ? ""
                                        : " - " + result.text());
        return new A2aSendResult(result.success(), detail);
    }
}
