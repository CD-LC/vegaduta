package com.agenticai.core.agents.tools.desktop;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfGuard;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The core side of {@code services/desktop-agent}: one RestClient pinned to the operator-configured
 * agent URL (its host, and only that host, is let past the private-address check - same pattern as
 * the workspace runner's client). The agent is a loopback HTTP service the person runs on their own
 * laptop; its {@code DESKTOP_AGENT_SECRET} must match {@code app.assistant.desktop.agent-secret}.
 */
final class DesktopAgentClient {

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final RestClient client;
    private final String secret;

    DesktopAgentClient(String agentUrl, String secret) {
        URI uri = URI.create(agentUrl);
        SsrfGuard guard = SsrfGuard.of(Set.of(uri.getHost()), InetAddress::getAllByName);
        this.client =
                RestClient.builder()
                        .baseUrl(agentUrl)
                        .requestFactory(
                                SafeHttpClients.requestFactory(
                                        guard,
                                        Duration.ofSeconds(5),
                                        Duration.ofSeconds(30),
                                        false))
                        .build();
        this.secret = secret;
    }

    /**
     * The agent's answer, or an {@link IOException} with the agent's own error text for the model.
     */
    JsonNode post(String path, Map<String, Object> body) throws IOException {
        try {
            String reply =
                    client.post()
                            .uri(path)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(MAPPER.writeValueAsString(body))
                            .retrieve()
                            .body(String.class);
            JsonNode parsed = reply == null ? null : MAPPER.readTree(reply);
            if (parsed == null) {
                throw new IOException("The desktop agent sent an unreadable reply.");
            }
            return parsed;
        } catch (RestClientResponseException e) {
            JsonNode err = parse(e.getResponseBodyAsString());
            String why =
                    err == null ? e.getStatusText() : err.path("error").asString(e.getStatusText());
            throw new IOException("The desktop agent refused the action: " + why, e);
        } catch (RuntimeException e) {
            throw new IOException(
                    "The desktop agent could not be reached - is it running? Start it with"
                            + " `node services/desktop-agent/server.js` on the laptop.",
                    e);
        }
    }

    JsonNode get(String path) throws IOException {
        try {
            String reply =
                    client.get()
                            .uri(path)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret)
                            .retrieve()
                            .body(String.class);
            JsonNode parsed = reply == null ? null : MAPPER.readTree(reply);
            if (parsed == null) {
                throw new IOException("The desktop agent sent an unreadable reply.");
            }
            return parsed;
        } catch (RestClientResponseException e) {
            JsonNode err = parse(e.getResponseBodyAsString());
            String why =
                    err == null ? e.getStatusText() : err.path("error").asString(e.getStatusText());
            throw new IOException("The desktop agent refused the action: " + why, e);
        } catch (RuntimeException e) {
            throw new IOException(
                    "The desktop agent could not be reached - is it running? Start it with"
                            + " `node services/desktop-agent/server.js` on the laptop.",
                    e);
        }
    }

    private static JsonNode parse(String json) {
        try {
            return json == null ? null : MAPPER.readTree(json);
        } catch (IOException e) {
            return null;
        }
    }
}
