package com.agenticai.core.agents.tools.desktop;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live-monitor view of the person's own screen: {@code GET /api/desktop/monitor/frame} proxies the
 * desktop-agent sidecar's {@code GET /frame}, passing the frame bytes, {@code Content-Type} and the
 * agent's {@code X-Frame-Format} header through untouched. This is a PROXY, not a re-encode - the
 * console renders whatever the laptop produced, so core never interprets the bytes.
 *
 * <p><b>Why the browser cannot call the agent directly.</b> The agent is a loopback HTTP service on
 * the person's laptop that demands {@code Authorization: Bearer <DESKTOP_AGENT_SECRET>}. Handing
 * the secret to the console would publish it to anyone who can open devtools, so the bearer stays
 * server-side: {@link DesktopAgentClient} adds it on the way out and it never appears in a request
 * or response this controller handles.
 *
 * <p><b>Gating.</b> Two conditions, both enforced the same way the tools themselves are: the whole
 * controller only exists when {@code app.assistant.desktop.monitor.enabled=true} (default off -
 * frames are a screenshot of whatever is on screen, so streaming them is its own opt-in), and the
 * endpoint answers 404 unless the desktop tools are on, which {@link DesktopToolSource#isEnabled()}
 * decides (single-user install AND a configured agent URL). Reusing that verdict keeps "desktop
 * enabled" a single source of truth: a monitor that answers frames while the tools are refused
 * would be a second, quieter way onto the same machine.
 *
 * <p><b>Auth.</b> Nothing extra: the default {@code anyRequest().authenticated()} chain covers
 * {@code /api/**} like every sibling controller (see {@code SecurityConfig}). Callers are whoever
 * can already log in - on the single-user installs this gate allows, that is the owner.
 *
 * <p><b>Rate limit.</b> Cheap per-instance single-flight: one frame fetch per caller at a time. The
 * agent takes a real screenshot per call, so a burst of parallel polls would queue screenshots on
 * the laptop; a second in-flight request gets a 429 and the console's poll simply retries on its
 * next tick. Per-caller rather than global so two watchers cannot starve each other; deliberately
 * not persisted - a restart just resets a one-request window.
 */
@RestController
@RequestMapping("/api/desktop/monitor")
@ConditionalOnProperty(name = "app.assistant.desktop.monitor.enabled", havingValue = "true")
public class DesktopMonitorController {

    private static final Logger log = LoggerFactory.getLogger(DesktopMonitorController.class);

    /**
     * Callers with a frame request in flight; emptied in a finally so a failure never wedges it.
     */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** The shared sidecar client, or null when the desktop tools are off and the endpoint 404s. */
    private final DesktopAgentClient client;

    public DesktopMonitorController(ObjectProvider<DesktopToolSource> desktop) {
        DesktopToolSource source = desktop.getIfAvailable();
        this.client = source != null && source.isEnabled() ? source.client() : null;
    }

    /**
     * The current screen frame. 404 when the desktop tools are off (the route then behaves like any
     * unknown path - whether a screen exists is itself host detail), 429 while that caller already
     * has a fetch in flight, 502 when the agent refuses or is not running.
     */
    @GetMapping("/frame")
    public ResponseEntity<?> frame(Principal principal) {
        DesktopAgentClient agent = client;
        if (agent == null) {
            return ResponseEntity.notFound().build();
        }
        // The caller key only scopes the single-flight set; anonymous falls back to one bucket,
        // which on the single-user installs this serves is still correct.
        String caller =
                principal != null && StringUtils.hasText(principal.getName())
                        ? principal.getName()
                        : "local";
        if (!inFlight.add(caller)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, "1")
                    .body(Map.of("error", "a frame request is already in flight for this caller"));
        }
        try {
            DesktopAgentClient.RawReply frame = agent.getRaw("/frame");
            ResponseEntity.BodyBuilder ok =
                    ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store");
            // Pass-through, verbatim strings: parsing the content type would only add a failure
            // mode, and X-Frame-Format is the console's decoding hint.
            if (StringUtils.hasText(frame.contentType())) {
                ok.header(HttpHeaders.CONTENT_TYPE, frame.contentType());
            }
            if (StringUtils.hasText(frame.frameFormat())) {
                ok.header(DesktopAgentClient.FRAME_FORMAT_HEADER, frame.frameFormat());
            }
            return ok.body(frame.body());
        } catch (IOException e) {
            // The agent's own error text stays in the log; the browser gets the generic kind so a
            // sidecar message can never carry laptop detail to a caller that only has a JWT.
            log.warn("desktop monitor frame failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "the desktop agent could not provide a frame"));
        } finally {
            inFlight.remove(caller);
        }
    }
}
