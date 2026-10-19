package com.agenticai.core.tenancy.demo;

import com.agenticai.core.tenancy.PlatformSettingsService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Shareable sandbox agents - the tenancy-side machinery behind {@code POST /api/demo/session/share}
 * (owner mints a link) and the public {@code /api/demo/share/{shareId}} surface (anyone with the
 * link talks to the owner's sandbox agent).
 *
 * <h2>What a share actually is</h2>
 *
 * One nullable column on {@code demo_session} (V131): an unguessable, URL-safe handle that resolves
 * to the SAME session, the same tenant schema, the same agent. A share is deliberately NOT a second
 * sandbox: a viewer's turns spend the owner session's own message allowance and ride the same
 * FREE-plan platform budget path the owner's turns do, so handing a link to five friends cannot
 * multiply the platform's anonymous spend - it only splits the existing allowance among more
 * people.
 *
 * <h2>Controls, in the order the chat endpoint applies them</h2>
 *
 * <ol>
 *   <li>Per-IP Redis rate limit ({@link DemoSessionRateLimiter #tryAcquireShareChat}) - the
 *       existing demo limiter pattern, its own namespace, admin-tunable.
 *   <li>Distinct-viewer cap ({@link #admitViewer}) - a Redis SET of viewer IPs per share, capped at
 *       {@code demo.share.viewer-cap} (default 5) DISTINCT addresses for the share's whole life,
 *       TTL aligned to the session's expiry. Not time-windowed on purpose: the product promise is
 *       "show it to a few people", not "5 concurrent".
 *   <li>The owner session's own message cap + the platform anonymous budget, enforced where they
 *       always were ({@code DemoSessionService}, {@code AgentChatService}'s FREE-tier path).
 *   <li>Moderation on the viewer's message before dispatch - the viewer is an even less trusted
 *       party than the anonymous owner.
 * </ol>
 *
 * <h2>Reports and revocation</h2>
 *
 * Anyone with the link can report it ({@link #report}); at {@code
 * demo.share.report-revoke-threshold} (default 3) reports the share auto-revokes and the public
 * surface starts returning 410. Revocation kills only the LINK, never the owner's sandbox - and is
 * deliberately never un-revoked by code (operator decision in SQL if a share was brigaded).
 */
@Service
public class DemoShareService {

    private static final Logger log = LoggerFactory.getLogger(DemoShareService.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 18 bytes -> 24 URL-safe Base64 chars (144 bits) - comfortably past the >=16-char contract.
     */
    private static final int SHARE_ID_BYTES = 18;

    /** Viewer-IP SET per share - see {@link #admitViewer}. */
    private static final String VIEWER_SET_KEY_PREFIX = "agentic:demo-share-viewers:";

    /**
     * What a well-formed share id can look like - anything else 404s without touching the database.
     */
    private static final Pattern SHARE_ID_SHAPE = Pattern.compile("[A-Za-z0-9_-]{16,64}");

    private final DemoSessionRepository demoSessionRepository;
    private final DemoSessionRecorder recorder;
    private final PlatformSettingsService platformSettings;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final String appPublicUrl;
    private final int defaultViewerCap;
    private final int defaultReportRevokeThreshold;

    public DemoShareService(
            DemoSessionRepository demoSessionRepository,
            DemoSessionRecorder recorder,
            PlatformSettingsService platformSettings,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            // Same property (and same NEVER-localhost default) the transcript
            // email and expiry reminder already build their links from.
            @Value("${app.web.public-url:}") String appPublicUrl,
            @Value("${app.demo.share.viewer-cap:5}") int defaultViewerCap,
            @Value("${app.demo.share.report-revoke-threshold:3}")
                    int defaultReportRevokeThreshold) {
        this.demoSessionRepository = demoSessionRepository;
        this.recorder = recorder;
        this.platformSettings = platformSettings;
        this.redisTemplateProvider = redisTemplateProvider;
        this.appPublicUrl = trimTrailingSlash(appPublicUrl);
        this.defaultViewerCap = defaultViewerCap;
        this.defaultReportRevokeThreshold = defaultReportRevokeThreshold;
    }

    public record ShareLink(String shareId, String shareUrl) {}

    /**
     * Mints (or returns the existing) share link for this session - idempotent, so a reload or
     * double-click hands back the URL already in circulation instead of quietly replacing it. The
     * winner of a genuine race is decided atomically in {@link DemoSessionRecorder
     * #assignShareIdIfAbsent}.
     */
    public ShareLink createOrGet(DemoSession session) {
        if (session.getShareId() != null) {
            return toLink(session.getShareId());
        }
        String assigned =
                recorder.assignShareIdIfAbsent(session.getId(), newShareId(), Instant.now());
        if (assigned == null) {
            // Session vanished between resolve and assign - treat like an
            // unknown token upstream; the controller 404s on empty resolves,
            // so this is the closest honest equivalent.
            throw new IllegalStateException(
                    "Demo session " + session.getId() + " vanished while sharing");
        }
        return toLink(assigned);
    }

    /**
     * Resolves a PUBLIC share id to its session. Shape-checked before the database sees it;
     * unknown, malformed and absent all come back empty, indistinguishably, same probing posture as
     * {@code DemoSessionService#resolve}. Expired/revoked sessions ARE returned - the controller
     * needs to tell 410 apart from 404.
     */
    public Optional<DemoSession> resolveByShareId(String shareId) {
        if (shareId == null || !SHARE_ID_SHAPE.matcher(shareId).matches()) {
            return Optional.empty();
        }
        return demoSessionRepository.findByShareId(shareId);
    }

    /** One public GET of the share meta - the funnel's shareViews counter. */
    public void recordView(DemoSession session) {
        recorder.recordShareView(session.getId());
    }

    /** How {@link #admitViewer} classified this viewer's address. */
    public enum ViewerAdmission {
        /** Already in the share's viewer set - no new slot consumed. */
        EXISTING,
        /** Admitted into a free slot; the durable viewer counter has been bumped. */
        NEW,
        /** The share has already been seen by its full allowance of distinct addresses. */
        CAP_REACHED
    }

    /**
     * The distinct-viewer cap: a Redis SET of viewer IPs keyed by share id, capped at {@code
     * demo.share.viewer-cap} DISTINCT addresses total (not per window), TTL pinned to the session's
     * expiry so the set dies with the sandbox.
     *
     * <p>Fails OPEN (as {@code EXISTING}) when Redis is absent, the same documented posture as
     * {@link DemoSessionRateLimiter}: the per-session message cap and the FREE-tier platform budget
     * still hold, and a demo surface that hard-fails when Redis blips would violate the sandbox's
     * never-broken contract.
     *
     * <p>Check-then-add rather than a Lua script, stated honestly: two first-time viewers racing on
     * the same share can momentarily land cap+1 distinct IPs. The cap is a product boundary ("show
     * it to a few people"), not a security boundary - the security boundaries are the budget and
     * message caps behind it - so one off-by-one under a deliberate race is not worth shipping a
     * script for.
     */
    public ViewerAdmission admitViewer(DemoSession session, String clientIp) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null || clientIp == null || clientIp.isBlank()) {
            return ViewerAdmission.EXISTING;
        }
        int cap = platformSettings.getInt("demo.share.viewer-cap", defaultViewerCap);
        String key = VIEWER_SET_KEY_PREFIX + session.getShareId();
        try {
            if (Boolean.TRUE.equals(redis.opsForSet().isMember(key, clientIp))) {
                return ViewerAdmission.EXISTING;
            }
            Long size = redis.opsForSet().size(key);
            if (size != null && size >= cap) {
                return ViewerAdmission.CAP_REACHED;
            }
            redis.opsForSet().add(key, clientIp);
            redis.expireAt(key, session.getExpiresAt());
            recorder.recordShareViewer(session.getId());
            return ViewerAdmission.NEW;
        } catch (RuntimeException e) {
            log.warn(
                    "Share viewer-cap check failed for share {} - failing open",
                    session.getShareId(),
                    e);
            return ViewerAdmission.EXISTING;
        }
    }

    /**
     * One abuse report against a share. The reason is logged for the operator, never persisted -
     * the durable fact is the count, and an unauthenticated free-text field is not something to
     * write to the database on a public endpoint.
     *
     * @return true when the share is (now) revoked.
     */
    public boolean report(DemoSession session, String reason) {
        int threshold =
                platformSettings.getInt(
                        "demo.share.report-revoke-threshold", defaultReportRevokeThreshold);
        boolean revoked = recorder.recordShareReport(session.getId(), Math.max(1, threshold));
        if (revoked) {
            log.info(
                    "Demo share {} auto-revoked after reaching the report threshold",
                    session.getShareId());
        }
        if (reason != null && !reason.isBlank()) {
            String trimmed = reason.strip();
            log.info(
                    "Demo share {} reported: {}",
                    session.getShareId(),
                    trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed);
        }
        return revoked;
    }

    /** The idempotent Academy-lab funnel events behind {@code POST /api/demo/session/event}. */
    public void recordLabStarted(DemoSession session) {
        recorder.recordLabStarted(session.getId(), Instant.now());
    }

    public void recordLabCompleted(DemoSession session) {
        recorder.recordLabCompleted(session.getId(), Instant.now());
    }

    // ------------------------------------------------------------------

    private ShareLink toLink(String shareId) {
        return new ShareLink(shareId, appPublicUrl + "/a/" + shareId);
    }

    private static String newShareId() {
        byte[] bytes = new byte[SHARE_ID_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
