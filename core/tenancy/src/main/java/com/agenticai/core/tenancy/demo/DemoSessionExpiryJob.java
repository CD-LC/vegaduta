package com.agenticai.core.tenancy.demo;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.PlatformSettingsService;

import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Hourly lifecycle tick for demo sandboxes - the same shape, and for the same stated reasons, as
 * {@code CompGrantExpiryReverter}: it queries the single {@code control_plane} table directly (a
 * demo session lives on the shared control plane, not inside any per-tenant schema), it is not
 * {@code @Profile}-gated, and it is a no-op on any deployment that has never started a sandbox.
 *
 * <p><b>Two passes per tick:</b>
 *
 * <ol>
 *   <li><b>Retire</b> sessions past {@code expires_at} - stamps {@code expired_at} (via {@link
 *       DemoSessionService#retire}, which also best-effort deletes this session's own seeded agent)
 *       and, since every visitor now shares one demo tenant, deliberately does NOT suspend anything
 *       tenant-wide. What actually stops the sandbox answering is {@code DemoSession#isExpired},
 *       which the demo chat/ image/video/channel controllers already check per-request.
 *   <li><b>Remind</b> live sessions inside the pre-expiry window that have not been nudged. Stamps
 *       {@code reminder_sent_at} EITHER WAY - see below.
 * </ol>
 *
 * <p><b>The reminder, honestly.</b> Most demo visitors never give an email address - the sandbox
 * does not ask for one, on purpose. For those sessions this pass has nothing to send and simply
 * stamps {@code reminder_sent_at} so the row stops being re-scanned every hour; that is a
 * "considered and skipped" marker, not a claim that mail went out. When an address IS present and a
 * {@link JavaMailSender} bean actually exists (it only does once {@code spring.mail.host} is
 * configured - same {@link ObjectProvider} treatment and same reasoning as {@code
 * NewsletterService}), a short plain reminder is sent. Mail failures are logged and swallowed: a
 * bounced reminder must never keep a lapsed sandbox alive or wedge the job for every other session
 * in the batch.
 *
 * <p><b>What this job deliberately does NOT do: drop the shared schema, or suspend the shared
 * tenant.</b> There is exactly one demo tenant/schema now (see {@code DemoSessionService}'s class
 * javadoc), and it stays ACTIVE for as long as {@code demo.enabled} is on - retiring one visitor's
 * session must never affect any other visitor still using the sandbox concurrently. Data this job's
 * cleanup does NOT reach (durable transcript/image/video rows, which stay {@code session_id}-keyed
 * in {@code control_plane} on purpose - see the class javadoc's "register to keep it" reasoning) is
 * retained past retirement exactly as before.
 *
 * <p>Multi-replica: each tick runs under {@link PgAdvisoryJobLock} as belt-and-braces, not for
 * correctness - both passes were already idempotent ({@code expired_at IS NULL} / {@code
 * reminder_sent_at IS NULL} filters plus a status-guarded UPDATE), and stay so. What the lock
 * removes is the one residual race the previous "no distributed lock is needed" stance accepted:
 * two replicas reading {@code reminder_sent_at IS NULL} in the same window could each send the
 * visitor a duplicate reminder email.
 */
@Component
public class DemoSessionExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(DemoSessionExpiryJob.class);
    private static final long TICK_MS = 3_600_000; // hourly

    private static final String JOB_LOCK_NAME = "demo-session-expiry";

    private final DemoSessionRepository demoSessionRepository;
    private final DemoSessionRecorder recorder;
    private final DemoSessionService demoSessionService;
    private final PlatformSettingsService platformSettings;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final PgAdvisoryJobLock jobLock;
    private final String fromAddress;
    private final String appPublicUrl;
    private final int defaultReminderHoursBefore;

    public DemoSessionExpiryJob(
            DemoSessionRepository demoSessionRepository,
            DemoSessionRecorder recorder,
            DemoSessionService demoSessionService,
            PlatformSettingsService platformSettings,
            ObjectProvider<JavaMailSender> mailSenderProvider,
            PgAdvisoryJobLock jobLock,
            @Value("${spring.mail.username:no-reply@example.com}") String fromAddress,
            @Value("${app.web.public-url:}") String appPublicUrl,
            @Value("${app.demo.reminder-hours-before-expiry:36}") int defaultReminderHoursBefore) {
        this.demoSessionRepository = demoSessionRepository;
        this.recorder = recorder;
        this.demoSessionService = demoSessionService;
        this.platformSettings = platformSettings;
        this.mailSenderProvider = mailSenderProvider;
        this.jobLock = jobLock;
        this.fromAddress = fromAddress;
        this.appPublicUrl = appPublicUrl;
        this.defaultReminderHoursBefore = defaultReminderHoursBefore;
    }

    @Scheduled(fixedRate = TICK_MS)
    public void tick() {
        // Lock-not-acquired is only DEBUG-logged (by the lock service itself) -
        // another replica ticking is success, not an incident.
        jobLock.tryRunExclusive(JOB_LOCK_NAME, this::tickExclusively);
    }

    private void tickExclusively() {
        Instant now = Instant.now();
        retireExpired(now);
        sendPreExpiryReminders(now);
    }

    private void retireExpired(Instant now) {
        List<DemoSession> due = demoSessionRepository.findDueForExpiry(now);
        for (DemoSession session : due) {
            try {
                demoSessionService.retire(session, now);
                log.info(
                        "Retired expired demo sandbox {} (shared tenant {}, schema {}) - session's"
                                + " own agent best-effort cleaned up, shared schema untouched",
                        session.getId(),
                        session.getTenantId(),
                        session.getTenantSchema());
            } catch (RuntimeException e) {
                // One bad row must not stop the rest of the batch - same
                // posture TenantSchemaIterator documents for its own fan-out.
                log.warn("Failed to retire demo sandbox {}", session.getId(), e);
            }
        }
    }

    private void sendPreExpiryReminders(Instant now) {
        int hoursBefore =
                platformSettings.getInt(
                        "demo.reminder-hours-before-expiry", defaultReminderHoursBefore);
        Instant cutoff = now.plus(Duration.ofHours(hoursBefore));
        List<DemoSession> due = demoSessionRepository.findDueForReminder(now, cutoff);
        if (due.isEmpty()) {
            return;
        }
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        for (DemoSession session : due) {
            try {
                if (session.getEmail() != null && mailSender != null) {
                    send(mailSender, session, now);
                    log.info("Sent pre-expiry reminder for demo sandbox {}", session.getId());
                } else if (session.getEmail() != null) {
                    log.warn(
                            "Demo sandbox {} has a reminder address but no JavaMailSender is"
                                + " configured (spring.mail.host unset) - marking considered, no"
                                + " mail sent",
                            session.getId());
                }
                // Stamped in every branch - see the class javadoc: this marks
                // "considered", not "delivered".
                recorder.markReminderSent(session.getId(), now);
            } catch (RuntimeException e) {
                // Covers org.springframework.mail.MailException (a
                // RuntimeException subtype) as well as anything unexpected -
                // a single catch, since a multi-catch listing both would not
                // compile: they are related by subclassing.
                log.warn(
                        "Pre-expiry reminder failed for demo sandbox {} - will not retry",
                        session.getId(),
                        e);
                recorder.markReminderSent(session.getId(), now);
            }
        }
    }

    private void send(JavaMailSender mailSender, DemoSession session, Instant now) {
        long days = session.daysRemaining(now);
        String when =
                days <= 0
                        ? "in less than a day"
                        : (days == 1 ? "tomorrow" : "in " + days + " days");
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(session.getEmail());
            helper.setSubject("Your VegaDūta sandbox ends " + when);
            helper.setText(
                    "Hi,\n\n"
                            + "The VegaDūta sandbox you started ends "
                            + when
                            + ".\n\n"
                            + "Creating a free account keeps you going: "
                            + appPublicUrl
                            + "/signup\n\n"
                            + "If you'd rather not, no action is needed - the sandbox simply"
                            + " stops.\n\n"
                            + "- VegaDūta\n");
            mailSender.send(message);
        } catch (jakarta.mail.MessagingException e) {
            throw new IllegalStateException("Could not build the demo reminder email", e);
        }
    }
}
