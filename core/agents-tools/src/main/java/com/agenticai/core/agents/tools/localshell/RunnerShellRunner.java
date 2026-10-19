package com.agenticai.core.agents.tools.localshell;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Runs the Assistant's commands in the workspace-runner container (services/workspace-runner)
 * instead of on core's own host: the toolchain image with only the projects folder mounted and no
 * platform secret. Used when core itself runs in a container, where the OS sandbox is not
 * available. The runner confines {@code folder} to its workspace root (symlinks included); this
 * class only passes it on.
 *
 * <p>The runner URL is operator configuration ({@code app.assistant.local-shell.runner-url}), so its
 * host, and only that host, is let past the private-address check.
 */
final class RunnerShellRunner implements ShellRunner {

    private static final ObjectMapper MAPPER = JsonMappers.plain();
    /** Head room over the command's own limit for the runner's kill grace and the reply. */
    private static final Duration REPLY_SLACK = Duration.ofSeconds(30);

    private final RestClient client;
    private final String secret;
    private final String workspaceLabel;
    private final int maxOutputChars;

    RunnerShellRunner(String runnerUrl, String secret, String workspaceLabel, int maxOutputChars, Duration maxTimeout) {
        URI uri = URI.create(runnerUrl);
        SsrfGuard guard = SsrfGuard.of(Set.of(uri.getHost()), InetAddress::getAllByName);
        this.client =
                RestClient.builder()
                        .baseUrl(runnerUrl)
                        .requestFactory(
                                SafeHttpClients.requestFactory(
                                        guard, Duration.ofSeconds(5), maxTimeout.plus(REPLY_SLACK), false))
                        .build();
        this.secret = secret;
        this.workspaceLabel = workspaceLabel;
        this.maxOutputChars = maxOutputChars;
    }

    @Override
    public String workspace() {
        return workspaceLabel;
    }

    @Override
    public String environmentNote() {
        return "It runs in a separate build container (JDK 21, Maven, Node 22 and npm, git, python3) that sees "
                + "only the person's projects folder, mounted as "
                + workspaceLabel
                + "; pass `folder` (relative to it) to work in the folder the person attached - the command then "
                + "already starts there, so do not cd into it. It has network "
                + "access for downloading dependencies, and nothing else of this computer.";
    }

    @Override
    public Result run(String command, Duration timeout) throws IOException {
        return run(command, "", timeout);
    }

    @Override
    public Result run(String command, String folder, Duration timeout) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("command", command);
        body.put("folder", folder == null ? "" : folder);
        body.put("timeoutSeconds", Math.max(1, timeout.toSeconds()));
        body.put("maxOutputChars", maxOutputChars);
        String reply;
        try {
            reply =
                    client.post()
                            .uri("/exec")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(MAPPER.writeValueAsString(body))
                            .retrieve()
                            .body(String.class);
        } catch (RestClientResponseException e) {
            JsonNode err = parse(e.getResponseBodyAsString());
            String why = err == null ? e.getStatusText() : err.path("error").asText(e.getStatusText());
            throw new IOException("The workspace runner refused the command: " + why, e);
        } catch (RuntimeException e) {
            throw new IOException("The workspace runner could not be reached: " + e.getMessage(), e);
        }
        JsonNode r = parse(reply);
        if (r == null) {
            throw new IOException("The workspace runner sent an unreadable reply.");
        }
        return new Result(
                r.path("exitCode").asInt(-1),
                r.path("output").asText(""),
                r.path("truncated").asBoolean(false),
                r.path("timedOut").asBoolean(false));
    }

    @Override
    public Server runBackground(String command, String folder) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("command", command);
        body.put("folder", folder == null ? "" : folder);
        body.put("background", true);
        JsonNode r = parse(post("/exec", MAPPER.writeValueAsString(body)));
        if (r == null) {
            throw new IOException("The workspace runner sent an unreadable reply.");
        }
        return new Server(
                r.path("port").asInt(-1),
                r.path("running").asBoolean(false),
                r.path("output").asText(""),
                r.path("exitCode").isNumber() ? r.path("exitCode").asInt() : null);
    }

    @Override
    public java.util.List<Preview> previews() throws IOException {
        String reply;
        try {
            reply =
                    client.get()
                            .uri("/dev")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret)
                            .retrieve()
                            .body(String.class);
        } catch (RuntimeException e) {
            throw new IOException("The workspace runner could not be reached: " + e.getMessage(), e);
        }
        JsonNode r = parse(reply);
        java.util.List<Preview> out = new java.util.ArrayList<>();
        if (r != null) {
            for (JsonNode p : r.path("previews")) {
                out.add(new Preview(p.path("port").asInt(), p.path("folder").asText(""), p.path("command").asText(""), p.path("running").asBoolean(false) && p.path("ready").asBoolean(true)));
            }
        }
        return out;
    }

    @Override
    public boolean stopPreview(int port) throws IOException {
        ObjectNode body = MAPPER.createObjectNode().put("port", port);
        JsonNode r = parse(post("/dev/stop", MAPPER.writeValueAsString(body)));
        return r != null && r.path("stopped").asBoolean(false);
    }

    private String post(String path, String json) throws IOException {
        try {
            return client.post()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            JsonNode err = parse(e.getResponseBodyAsString());
            String why = err == null ? e.getStatusText() : err.path("error").asText(e.getStatusText());
            throw new IOException("The workspace runner refused the request: " + why, e);
        } catch (RuntimeException e) {
            throw new IOException("The workspace runner could not be reached: " + e.getMessage(), e);
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
