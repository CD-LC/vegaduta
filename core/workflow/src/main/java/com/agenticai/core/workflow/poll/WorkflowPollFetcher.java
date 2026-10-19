package com.agenticai.core.workflow.poll;

import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfGuard;
import com.agenticai.core.security.net.SsrfBlockedException;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.HttpEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The one outbound HTTP fetch for poll triggers. Every connection goes
 * through {@link SsrfGuard}'s DNS resolver ({@link SafeHttpClients}) - the
 * host is resolved once, every address is checked, and the socket goes only
 * to a checked address, so a tenant-configured URL cannot reach internal
 * network targets and DNS rebinding does not reopen the gap. Redirects are
 * OFF: configured headers can carry credentials, and following a redirect
 * would send them to a host the tenant did not configure - a 3xx is reported
 * as a poll failure telling the operator to configure the final URL.
 *
 * <p>The stored URL is additionally pre-checked with {@link
 * SsrfGuard#validateWithoutDns} before the request is built: cheap (no DNS
 * lookup - the guarded resolver does that once at connect time), but catches
 * non-http(s) schemes and internal host names without waiting for a connect.
 */
@Component
public class WorkflowPollFetcher {

    private static final Logger log = LoggerFactory.getLogger(WorkflowPollFetcher.class);
    private static final String USER_AGENT = "VegaDuta-Poll/1.0";

    private final WorkflowPollProperties properties;
    private final SsrfGuard guard;
    private volatile CloseableHttpClient client;

    // @Autowired marks the wiring ctor: the package-private test seam below is a
    // second ctor, and with two candidates Spring falls back to a (nonexistent)
    // default ctor instead of guessing - "No default constructor found" at boot.
    @org.springframework.beans.factory.annotation.Autowired
    public WorkflowPollFetcher(WorkflowPollProperties properties) {
        this(properties, SsrfGuard.defaultGuard());
    }

    /** Test seam: a guard over a stubbed resolver. */
    WorkflowPollFetcher(WorkflowPollProperties properties, SsrfGuard guard) {
        this.properties = properties;
        this.guard = guard;
    }

    /**
     * Fetches {@code url} with the decrypted per-trigger headers and returns
     * the response body. Throws {@link PollFetchException} on anything that is
     * not a 2xx within the size cap.
     */
    public String fetch(String url, String method, Map<String, String> headers) {
        try {
            guard.validateWithoutDns(url);
        } catch (SsrfBlockedException e) {
            throw new PollFetchException("refused by the outbound URL guard: " + e.getMessage(), e);
        }
        HttpUriRequestBase request = switch (method.toUpperCase(java.util.Locale.ROOT)) {
            case "GET" -> new HttpGet(url);
            case "POST" -> new HttpPost(url);
            default -> throw new PollFetchException("unsupported poll method " + method);
        };
        request.setHeader("User-Agent", USER_AGENT);
        request.setHeader("Accept", "application/json");
        if (headers != null) {
            headers.forEach(request::setHeader);
        }
        try {
            return client().execute(request, response -> {
                int status = response.getCode();
                if (status < 200 || status >= 300) {
                    throw new PollFetchException("endpoint answered HTTP " + status);
                }
                HttpEntity entity = response.getEntity();
                if (entity == null) {
                    return "";
                }
                if (entity.getContentLength() > properties.getMaxResponseBytes()) {
                    throw new PollFetchException("response is larger than the "
                        + properties.getMaxResponseBytes() + " byte poll cap");
                }
                return readBounded(entity.getContent());
            });
        } catch (PollFetchException e) {
            throw e;
        } catch (Exception e) {
            // DNS/SSRF refusal surfaces here as a nested UnknownHostException subtype.
            throw new PollFetchException("poll fetch failed: " + e.getMessage(), e);
        }
    }

    private String readBounded(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > properties.getMaxResponseBytes()) {
                throw new PollFetchException("response is larger than the "
                    + properties.getMaxResponseBytes() + " byte poll cap");
            }
            out.write(buf, 0, n);
        }
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private CloseableHttpClient client() {
        CloseableHttpClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    client = SafeHttpClients.create(
                        guard, properties.getConnectTimeout(), properties.getResponseTimeout(), false);
                }
                c = client;
            }
        }
        return c;
    }

    @PreDestroy
    void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("poll fetcher client close failed: {}", e.getMessage());
            }
        }
    }
}
