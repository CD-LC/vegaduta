package com.agenticai.core.platform;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Landing-page funnel beacon ({@code POST /api/public/landing-event}) and its
 * platform-admin read-out ({@code GET /api/platform/landing-metrics}). Exists
 * because the landing page had ZERO funnel measurement (2026-07-28 audit) -
 * every redesign of the hero/CTAs so far has been argued from taste, not from
 * whether anyone actually clicked them.
 *
 * <p><b>Why Redis counters, not database rows:</b> this endpoint is hit by
 * every anonymous visitor on the single most public page the platform has, so
 * per-hit rows would be an unbounded, attacker-growable table needing a
 * migration, an index, and a retention job on day one. A fixed whitelist of
 * event names x one key per UTC day is instead a hard-bounded keyspace
 * (96 events x 90 retained days = at most 8640 keys, self-expiring via
 * {@code EXPIRE}), stores no PII whatsoever - no IP, no user agent, no
 * referrer, just an integer per event per day - and needs no Flyway migration
 * at all. Counts are exactly what the funnel question needs ("how many
 * landing views became try-clicks"), and nothing more is worth the liability.
 *
 * <p><b>Why fail-open and always 204:</b> same reasoning as {@code
 * DemoSessionRateLimiter}'s missing-Redis branch - this is a measurement
 * beacon, not a feature. A narrow test context (or a Redis outage) must never
 * turn analytics into a visitor-facing error, and the landing page fires these
 * with {@code navigator.sendBeacon}-style fire-and-forget semantics that can't
 * act on a response anyway. So: unknown event name, over the abuse lid, Redis
 * down, malformed body - every path answers 204 No Content and the only
 * difference is whether a counter moved. {@link StringRedisTemplate} is
 * injected through an {@link ObjectProvider} for the same reason it is in the
 * demo rate limiter: platform beans must construct cleanly in contexts with no
 * Redis autoconfiguration at all.
 *
 * <p><b>Abuse posture:</b> the only thing an abuser can do here is inflate the
 * platform's own vanity metrics - there is no per-visitor state to corrupt and
 * no cost line attached. The per-IP hourly lid (300 events/hour, its own
 * {@code INCR}+{@code EXPIRE} window) exists to keep one bored script from
 * making the numbers useless, not to defend anything valuable.
 *
 * <p><b>Why the lid moved 60 -> 300 on 2026-09-11:</b> before the
 * section-reach events a whole visit was ~2 events, so 60/hour was ~30
 * visitors per address. With 16 {@code sec_*} events a single thorough visit
 * is ~20-30, and 60 would start dropping events after the third visitor
 * behind one NAT or CGNAT address. Worse, it would drop them <b>in scroll
 * order</b> - the visitor's later events are the ones that hit the lid - so
 * the late bands would lose counts first and the read-out would render a
 * sharp drop-off cliff at the bottom of the page that is purely an artefact
 * of this constant. A lid that silently fabricates the exact shape the
 * dashboard exists to measure is worse than no lid; 300 keeps the
 * bored-script protection with roughly the same visitors-per-address
 * headroom the original 60 gave. Honest caveat,
 * same as every per-IP control in this codebase: an address pool walks through
 * it; the defense against that is that inflated counters are worthless to the
 * attacker.
 *
 * <p>The admin read side is gated by the same {@code SecurityConfig}
 * {@code .requestMatchers("/api/platform/**").hasAuthority("ROLE_platform-admin")}
 * wildcard every other controller under that prefix inherits (see {@code
 * DemoSandboxController}'s javadoc for that posture) - no separate
 * authorization check in this class.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@RestController
public class LandingEventController {

    private static final Logger log = LoggerFactory.getLogger(LandingEventController.class);

    /**
     * The complete funnel vocabulary. Fixed at compile time on purpose: the
     * event name is interpolated into a Redis key, so an open vocabulary would
     * hand anonymous callers an unbounded keyspace. Anything not in this set
     * is dropped silently (204) - adding a funnel step means adding it here,
     * nowhere else.
     */
    private static final Set<String> ALLOWED_EVENTS = Set.of(
        "landing_view",
        "hero_try_click",
        "hero_watch_click",
        "signup_click",
        "magic_run_click",
        "closing_try_click",
        "closing_signup_click",
        // /hire "Hiring Papers" landing (web/app/components/hire-landing/hireBeacon.ts)
        "hire_view",
        "hire_interview_answer",
        "hire_stamp_click",
        "hire_calc_interact",
        "hire_sign_draw",
        "hire_cta_click",
        // workforce-shift + automate-anything landing bands (2026-08-03,
        // web/app/components/demo-landing/landingBeacon.ts)
        "workforce_dev_academy_click",
        "workforce_exec_hire_click",
        "automate_try_click",
        "automate_templates_click",
        // "contact our team" support line, same band (2026-08-12)
        "automate_contact_click",
        // Outcomes band + signup funnel (2026-09-17, web/app/components/demo-landing/
        // landingBeacon.ts and web/app/signup). Requested by the web landing
        // rebuild: without a server-side allowlist entry each of these is
        // dropped at LandingEventController's `!ALLOWED_EVENTS.contains(event)`
        // guard before Redis is touched, so the beacon fires, the browser sees
        // 204, and the counter silently stays zero — the same
        // looks-configured-does-nothing shape that cost this codebase five
        // separate bugs today.
        "hero_email_start",
        "hero_persona_click",
        "outcomes_signup_click",
        "outcomes_try_click",
        "signup_view",
        "signup_submit",
        "signup_done",
        "atlas_tile_click",
        // entrepreneur band + /earn playbook page (2026-08-03)
        "entrepreneur_earn_click",
        "earn_academy_click",
        "earn_signup_click",
        // previously un-instrumented landing click sites (funnel audit,
        // 2026-09-09): nav Sign in, pricing Free CTA, senses /try button,
        // IDE-plugins /plugins link, /hire chip
        "nav_signin_click",
        "pricing_free_click",
        // Open-source band CTA (2026-09-22): the band replaced PricingBand
        // in the same funnel slot, so it sits next to pricing_free_click.
        "oss_try_click",
        "senses_try_click",
        "plugins_click",
        "hire_banner_click",
        // "The Path" conversion redesign (2026-09-10): hero journey-aware
        // buttons + the Try -> Understand -> Implement band (PathBand.tsx)
        "hero_keep_agent_click",
        "hero_signup_click",
        "path_try_click",
        "path_academy_click",
        "path_templates_click",
        "path_signup_click",
        // conversion band (2026-09-10): BusinessCaseBand's free case-study
        // offer + ProviderWall (bring your own key / use ours / on-device).
        // casestudy_contact_click is deliberately its OWN name rather than
        // reusing automate_contact_click - both are mailto:contact@example.com
        // clicks, and one shared counter would make the two offers
        // indistinguishable in the read-out.
        "casestudy_industry_click",
        "casestudy_contact_click",
        "casestudy_signup_click",
        "casestudy_academy_click",
        "providerwall_signup_click",
        "providerwall_learn_click",
        // Conversion rebuild (2026-09-12): PlatformLedger (the breadth band)
        // and ContactBand (the first ask-a-human banner this page has had).
        // contact_band_click is a NEW name, deliberately NOT a revival of the
        // dead automate_contact_click above - that counter has been frozen
        // since its component was unmounted on 2026-08-14, and reusing it
        // would splice a new band onto weeks of flat line.
        "hero_console_signup_click",
        "magic_signup_click",
        "ledger_signup_click",
        "ledger_academy_click",
        "contact_band_click",
        "contact_signup_click",
        // Section-reach funnel (2026-09-11): one `sec_<band>` per landing band,
        // in DemoLanding.tsx render order. These are the ONLY events here that
        // are not clicks - each records that a visitor actually scrolled to a
        // band and held it on screen for a continuous second (web's
        // SectionReach.tsx). They exist because a click count alone cannot
        // distinguish "nobody reached pricing" from "pricing converted
        // nobody" - the question the 2026-09-09 audit above set out to answer
        // and could not.
        "sec_hero",
        "sec_outcomes",
        "sec_path",
        "sec_ledger",
        "sec_channels",
        "sec_senses",
        "sec_proof",
        "sec_audience",
        "sec_studio",
        "sec_channeldemos",
        "sec_underneath",
        "sec_ide",
        "sec_security",
        "sec_providers",
        "sec_pricing",
        "sec_casestudy",
        "sec_faq",
        "sec_contact",
        "sec_closing",
        // Events web already fires that were being 204-dropped (flat zeros in
        // the funnel read-out): the "Just shipped" band (ShippedBand.tsx) and
        // the section-reach beacons of three bands added after the original
        // sec_* set (category, control, use cases).
        "sec_shipped",
        "shipped_signup_click",
        "shipped_try_click",
        "sec_category",
        "sec_control",
        "sec_usecases",
        // /start onboarding flow (2026-10-04, web/app/start/events.ts) - the
        // time-to-first-successful-agent funnel. The beacon takes only a name,
        // so each parameter (goal id, skipped step, elapsed-time bucket) is part
        // of the name: a closed, countable set with no payload and no free text.
        // The five first_run buckets split onboarding_first_run_completed by
        // time since the first /start visit (lt5m = under five minutes).
        "onboarding_start",
        "onboarding_goal_selected_learn",
        "onboarding_goal_selected_research",
        "onboarding_goal_selected_analyze",
        "onboarding_goal_selected_automate",
        "onboarding_goal_selected_draft",
        "onboarding_goal_selected_build",
        "onboarding_agent_created",
        "onboarding_prompt_prefilled",
        "onboarding_first_run_completed",
        "onboarding_first_run_lt1m",
        "onboarding_first_run_lt2m",
        "onboarding_first_run_lt5m",
        "onboarding_first_run_lt10m",
        "onboarding_first_run_gte10m",
        "onboarding_skipped_goal",
        "onboarding_skipped_create",
        "onboarding_skipped_try"
    );

    /** Kept in declaration order for a stable JSON shape in the admin read-out. */
    private static final List<String> ALLOWED_EVENTS_ORDERED = List.of(
        "landing_view",
        "hero_try_click",
        "hero_watch_click",
        "signup_click",
        "magic_run_click",
        "closing_try_click",
        "closing_signup_click",
        "hire_view",
        "hire_interview_answer",
        "hire_stamp_click",
        "hire_calc_interact",
        "hire_sign_draw",
        "hire_cta_click",
        "workforce_dev_academy_click",
        "workforce_exec_hire_click",
        "automate_try_click",
        "automate_templates_click",
        "automate_contact_click",
        "entrepreneur_earn_click",
        "earn_academy_click",
        "earn_signup_click",
        "nav_signin_click",
        "pricing_free_click",
        // Open-source band CTA (2026-09-22): same funnel slot as the pricing
        // band it replaced.
        "oss_try_click",
        "senses_try_click",
        "plugins_click",
        "hire_banner_click",
        // "The Path" conversion redesign (2026-09-10): hero journey-aware
        // buttons + the Try -> Understand -> Implement band (PathBand.tsx)
        "hero_keep_agent_click",
        "hero_signup_click",
        "path_try_click",
        "path_academy_click",
        "path_templates_click",
        "path_signup_click",
        // conversion band (2026-09-10): BusinessCaseBand + ProviderWall
        "casestudy_industry_click",
        "casestudy_contact_click",
        "casestudy_signup_click",
        "casestudy_academy_click",
        "providerwall_signup_click",
        "providerwall_learn_click",
        // Conversion rebuild (2026-09-12): PlatformLedger (the breadth band)
        // and ContactBand (the first ask-a-human banner this page has had).
        // contact_band_click is a NEW name, deliberately NOT a revival of the
        // dead automate_contact_click above - that counter has been frozen
        // since its component was unmounted on 2026-08-14, and reusing it
        // would splice a new band onto weeks of flat line.
        "hero_console_signup_click",
        "magic_signup_click",
        "ledger_signup_click",
        "ledger_academy_click",
        "contact_band_click",
        "contact_signup_click",
        // Section-reach funnel (2026-09-11): one `sec_<band>` per landing band,
        // in DemoLanding.tsx render order. These are the ONLY events here that
        // are not clicks - each records that a visitor actually scrolled to a
        // band and held it on screen for a continuous second (web's
        // SectionReach.tsx). They exist because a click count alone cannot
        // distinguish "nobody reached pricing" from "pricing converted
        // nobody" - the question the 2026-09-09 audit above set out to answer
        // and could not.
        "sec_hero",
        "sec_outcomes",
        "sec_path",
        "sec_ledger",
        "sec_channels",
        "sec_senses",
        "sec_proof",
        "sec_audience",
        "sec_studio",
        "sec_channeldemos",
        "sec_underneath",
        "sec_ide",
        "sec_security",
        "sec_providers",
        "sec_pricing",
        "sec_casestudy",
        "sec_faq",
        "sec_contact",
        "sec_closing",
        // Events web already fires that were being 204-dropped (flat zeros in
        // the funnel read-out): the "Just shipped" band (ShippedBand.tsx) and
        // the section-reach beacons of three bands added after the original
        // sec_* set (category, control, use cases).
        "sec_shipped",
        "shipped_signup_click",
        "shipped_try_click",
        "sec_category",
        "sec_control",
        "sec_usecases",
        // /start onboarding flow (2026-10-04, web/app/start/events.ts) - the
        // time-to-first-successful-agent funnel. The beacon takes only a name,
        // so each parameter (goal id, skipped step, elapsed-time bucket) is part
        // of the name: a closed, countable set with no payload and no free text.
        // The five first_run buckets split onboarding_first_run_completed by
        // time since the first /start visit (lt5m = under five minutes).
        "onboarding_start",
        "onboarding_goal_selected_learn",
        "onboarding_goal_selected_research",
        "onboarding_goal_selected_analyze",
        "onboarding_goal_selected_automate",
        "onboarding_goal_selected_draft",
        "onboarding_goal_selected_build",
        "onboarding_agent_created",
        "onboarding_prompt_prefilled",
        "onboarding_first_run_completed",
        "onboarding_first_run_lt1m",
        "onboarding_first_run_lt2m",
        "onboarding_first_run_lt5m",
        "onboarding_first_run_lt10m",
        "onboarding_first_run_gte10m",
        "onboarding_skipped_goal",
        "onboarding_skipped_create",
        "onboarding_skipped_try"
    );

    private static final String COUNTER_KEY_PREFIX = "agentic:landing:";
    private static final String RATE_KEY_PREFIX = "agentic:landing-rate:";
    private static final Duration COUNTER_TTL = Duration.ofDays(90);
    private static final Duration RATE_WINDOW = Duration.ofHours(1);
    private static final int MAX_EVENTS_PER_IP_PER_HOUR = 300;

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH");

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    /** Resolves only when {@code app.landing-events.batch-enabled=true}; see {@link LandingEventBuffer}. */
    private final ObjectProvider<LandingEventBuffer> bufferProvider;

    public LandingEventController(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this(redisTemplateProvider, null);
    }

    @Autowired
    public LandingEventController(
        ObjectProvider<StringRedisTemplate> redisTemplateProvider,
        ObjectProvider<LandingEventBuffer> bufferProvider
    ) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.bufferProvider = bufferProvider;
    }

    /** Beacon body - just the event name; anything else a caller sends is ignored. */
    public record LandingEventRequest(String event) {}

    /** One day of the admin read-out: UTC date plus every whitelisted event's count. */
    public record LandingMetricsDay(String date, Map<String, Long> events) {}

    /** Admin read-out envelope - oldest day first, ending today (UTC). */
    public record LandingMetricsResponse(List<LandingMetricsDay> days) {}

    /**
     * The beacon. Always 204 No Content, no matter what - see class javadoc
     * for why silence is the contract on every path.
     */
    @PostMapping("/api/public/landing-event")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void record(
        @RequestBody(required = false) LandingEventRequest request,
        HttpServletRequest servletRequest
    ) {
        String event = request == null ? null : request.event();
        if (event == null || !ALLOWED_EVENTS.contains(event)) {
            return; // not in the vocabulary - drop before Redis is even touched
        }
        LandingEventBuffer buffer = bufferProvider == null ? null : bufferProvider.getIfAvailable();
        if (buffer != null) {
            // opt-in batch mode: accept now, the IP lid + counter run at flush time. Same 204.
            buffer.offer(event, clientIp(servletRequest));
            return;
        }
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return; // see class javadoc - fail open, this is measurement, not a feature
        }
        try {
            if (!withinIpLid(redisTemplate, clientIp(servletRequest))) {
                return; // over the hourly lid - drop silently, never an error
            }
            String key = COUNTER_KEY_PREFIX + event + ":" + LocalDate.now(ZoneOffset.UTC).format(DAY_FORMAT);
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, COUNTER_TTL);
            }
        } catch (RuntimeException e) {
            // Redis reachable-but-failing is the same as Redis absent here:
            // losing a counter tick must never surface to a landing visitor.
            log.debug("landing-event beacon dropped (Redis error): {}", e.getMessage());
        }
    }

    /**
     * Same fixed-window {@code INCR}+conditional-{@code EXPIRE} shape as
     * {@code DemoSessionRateLimiter#tryAcquire}, inlined rather than shared
     * because that class is another module's (tenancy's) and this is the whole
     * of what's needed. The window key carries the UTC hour so the counter
     * self-partitions even if the {@code EXPIRE} call is lost.
     */
    private boolean withinIpLid(StringRedisTemplate redisTemplate, String clientIp) {
        String key = RATE_KEY_PREFIX + clientIp + ":" + LocalDateTime.now(ZoneOffset.UTC).format(HOUR_FORMAT);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) {
            return true;
        }
        if (count == 1L) {
            redisTemplate.expire(key, RATE_WINDOW);
        }
        return count <= MAX_EVENTS_PER_IP_PER_HOUR;
    }

    /**
     * {@code request.getRemoteAddr()} - deliberately NOT trusting
     * {@code X-Forwarded-For} directly at this layer, identical to
     * {@code DemoSessionController#clientIp} and for the identical reason: a
     * client-settable header, trusted blindly, would let an attacker present a
     * fresh IP per request and walk straight through the hourly lid. Tomcat's
     * {@code RemoteIpValve} (see {@code server.tomcat.remoteip.trusted-proxies}
     * in {@code application.yml}) already resolves this to the real visitor IP
     * when the request actually came through Caddy.
     */
    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /**
     * A body this endpoint can't parse is just another unknown event: 204,
     * never a 400 into an anonymous visitor's console. Scoped to the parse
     * exception only - the admin GET below keeps normal error semantics.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void onUnreadableBeacon() {
        // deliberately empty - the beacon contract is silence
    }

    /**
     * The funnel read-out: per-UTC-day counts for every whitelisted event over
     * the trailing window, oldest day first, ending today. One {@code MGET}
     * for the whole whitelist x date grid; a key Redis doesn't have (day with
     * no traffic, or expired past the 90-day TTL) reads as 0. Redis absent
     * reads as all zeros rather than an error - the operator sees an empty
     * funnel, which is the truth of what was measured.
     *
     * @param days window length. Clamped rather than rejected, same reasoning
     *     as {@code DemoSandboxController#getDemoMetrics}: a hand-edited
     *     {@code ?days=100000} should give the operator a chart, not a 400.
     */
    @GetMapping("/api/platform/landing-metrics")
    public LandingMetricsResponse metrics(@RequestParam(name = "days", defaultValue = "14") int days) {
        int window = Math.max(1, Math.min(90, days));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> dates = new ArrayList<>(window);
        for (int i = window - 1; i >= 0; i--) {
            dates.add(today.minusDays(i));
        }

        List<String> keys = new ArrayList<>(window * ALLOWED_EVENTS_ORDERED.size());
        for (LocalDate date : dates) {
            String daySuffix = ":" + date.format(DAY_FORMAT);
            for (String event : ALLOWED_EVENTS_ORDERED) {
                keys.add(COUNTER_KEY_PREFIX + event + daySuffix);
            }
        }

        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        List<String> values = null;
        if (redisTemplate != null) {
            try {
                values = redisTemplate.opsForValue().multiGet(keys);
            } catch (RuntimeException e) {
                // Same stance as the beacon above and as "Redis absent" in this method's javadoc: the
                // operator sees an empty funnel, not a 500 on the admin page while Redis is down.
                log.debug("landing-metrics read skipped (Redis error): {}", e.getMessage());
            }
        }

        List<LandingMetricsDay> result = new ArrayList<>(window);
        int cursor = 0;
        for (LocalDate date : dates) {
            Map<String, Long> events = new LinkedHashMap<>();
            for (String event : ALLOWED_EVENTS_ORDERED) {
                String raw = values == null ? null : values.get(cursor);
                events.put(event, parseCount(raw));
                cursor++;
            }
            result.add(new LandingMetricsDay(date.format(DAY_FORMAT), events));
        }
        return new LandingMetricsResponse(result);
    }

    private static long parseCount(String raw) {
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L; // a mangled counter reads as empty, not as a 500
        }
    }
}
