package com.agenticai.core.common.privacy;

import com.agenticai.core.agents.consent.ConversationConsentGate;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.common.privacy.ConsentLedger.Decision;
import com.agenticai.core.common.privacy.ConsentPolicyService.Policy;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * The optional first-message notice and consent step for channel conversations. Active only for an
 * agent whose {@link Policy} is enabled, and only for turns that belong to a {@code channel_session}
 * (so the console Playground, workflows and API calls pass straight through).
 *
 * <p>While the contact has no GRANTED consent for the current notice version, the turn is replaced
 * by the notice (or a short acknowledgement of their answer): no model call, no tool, no memory
 * write, and (via {@link #mayStoreContent}) no transcript row for their message. A contact's
 * answer is read from the message itself ({@link ConsentText}); anything that is not a clear
 * "agree" leaves them unconsented. Withdrawal ("STOP") after consent ends processing again and, if
 * the policy says so, erases the contact's data.
 */
@Component
public class ConsentGateService implements ConversationConsentGate {

    private static final Logger log = LoggerFactory.getLogger(ConsentGateService.class);
    static final String ACTOR = "system:consent-gate";

    public static final String THANKS =
            "Thank you, your consent is recorded. You can withdraw it at any time by replying STOP."
                    + " Please send your message again.";
    public static final String DECLINED =
            "Understood, we will not process your messages. Reply YES if you change your mind.";
    public static final String WITHDRAWN =
            "Your consent is withdrawn and we have stopped processing your messages.";
    public static final String WITHDRAWN_AND_ERASED =
            WITHDRAWN + " Your data held for this conversation has been erased.";

    private final JdbcTemplate jdbc;
    private final ConsentPolicyService policies;
    private final ConsentLedger ledger;
    private final ContactPrivacyService contacts;
    private final AuditLogService audit;

    public ConsentGateService(
            JdbcTemplate jdbc,
            ConsentPolicyService policies,
            ConsentLedger ledger,
            ContactPrivacyService contacts,
            AuditLogService audit) {
        this.jdbc = jdbc;
        this.policies = policies;
        this.ledger = ledger;
        this.contacts = contacts;
        this.audit = audit;
    }

    @Override
    public Flux<String> intercept(UUID agentId, String sessionId, String subjectKey, String userMessage) {
        Optional<Policy> policy = policies.find(agentId).filter(Policy::enabled);
        if (policy.isEmpty()) {
            return null;
        }
        Optional<Contact> contact = contactForSession(sessionId);
        if (contact.isEmpty()) {
            return null; // not a channel conversation
        }
        String reply = decide(policy.get(), contact.get(), agentId, userMessage);
        return reply == null ? null : Flux.just(reply);
    }

    @Override
    public boolean mayStoreContent(UUID agentId, String channel, String externalId) {
        Optional<Policy> policy = policies.find(agentId).filter(Policy::enabled);
        return policy.isEmpty() || hasConsent(policy.get(), channel, externalId);
    }

    /** @return the reply to send instead of running the turn, or null to let it run. */
    String decide(Policy policy, Contact contact, UUID agentId, String message) {
        Optional<ConsentLedger.Entry> latest =
                ledger.latest(contact.channel(), contact.externalId(), policy.purpose());
        boolean granted = isCurrentGrant(latest, policy);
        ConsentText.Parsed parsed = ConsentText.parse(message, policy.keywordSet());

        if (granted) {
            if (parsed.intent() != ConsentText.Intent.WITHDRAW) {
                return null;
            }
            return withdraw(policy, contact, agentId, latest.get());
        }
        switch (parsed.intent()) {
            case ACCEPT:
                {
                    // Consent attaches to the notice the contact was last shown, in its language.
                    String language = promptedLanguage(latest, policy);
                    String text = noticeFor(policy, language);
                    record(policy, contact, agentId, language, text, Decision.GRANTED);
                    return THANKS;
                }
            case DECLINE:
                {
                    String language = promptedLanguage(latest, policy);
                    record(policy, contact, agentId, language, noticeFor(policy, language), Decision.DECLINED);
                    return DECLINED;
                }
            case WITHDRAW:
                {
                    // Nothing to withdraw; do not store anything, just stop.
                    return WITHDRAWN;
                }
            case LANGUAGE:
                {
                    String language =
                            policy.notices().containsKey(parsed.language())
                                    ? parsed.language()
                                    : policy.defaultLanguage();
                    return prompt(policy, contact, agentId, language, true);
                }
            default:
                return prompt(policy, contact, agentId, promptedLanguage(latest, policy), false);
        }
    }

    private String withdraw(Policy policy, Contact contact, UUID agentId, ConsentLedger.Entry current) {
        record(policy, contact, agentId, current.noticeLanguage(), noticeFor(policy, current.noticeLanguage()), Decision.WITHDRAWN);
        boolean erased = false;
        if (policy.eraseOnWithdrawal()) {
            ContactPrivacyService.ErasureReport report =
                    contacts.eraseOnWithdrawal(contact.channel(), contact.externalId());
            erased = report.complete();
            if (!erased) {
                log.warn("Erasure on consent withdrawal was incomplete: {}", report.failures());
            }
        }
        return erased ? WITHDRAWN_AND_ERASED : WITHDRAWN;
    }

    private String prompt(Policy policy, Contact contact, UUID agentId, String language, boolean force) {
        String text = noticeFor(policy, language);
        Optional<ConsentLedger.Entry> latest =
                ledger.latest(contact.channel(), contact.externalId(), policy.purpose());
        boolean alreadyPrompted =
                latest.isPresent()
                        && latest.get().decision() == Decision.PROMPTED
                        && latest.get().noticeVersion() == policy.noticeVersion()
                        && latest.get().noticeLanguage().equals(language);
        if (force || !alreadyPrompted) {
            record(policy, contact, agentId, language, text, Decision.PROMPTED);
        }
        return ConsentText.compose(text, policy.instructions().get(language));
    }

    private void record(
            Policy policy, Contact contact, UUID agentId, String language, String text, Decision decision) {
        ledger.record(
                contact.channel(),
                contact.externalId(),
                agentId,
                policy.purpose(),
                policy.noticeVersion(),
                language,
                ConsentText.sha256(text),
                decision);
        if (decision != Decision.PROMPTED) {
            audit.record(
                    ACTOR,
                    "privacy.consent." + decision.name().toLowerCase(),
                    "channel_contact",
                    ContactPrivacyService.mask(contact.externalId()),
                    AuditOutcome.SUCCESS,
                    decision
                            + " for purpose '"
                            + policy.purpose()
                            + "', notice v"
                            + policy.noticeVersion()
                            + " ("
                            + language
                            + ") on "
                            + contact.channel());
        }
    }

    private boolean hasConsent(Policy policy, String channel, String externalId) {
        return isCurrentGrant(ledger.latest(channel, externalId, policy.purpose()), policy);
    }

    private static boolean isCurrentGrant(Optional<ConsentLedger.Entry> latest, Policy policy) {
        return latest.isPresent()
                && latest.get().decision() == Decision.GRANTED
                && latest.get().noticeVersion() == policy.noticeVersion();
    }

    private static String promptedLanguage(Optional<ConsentLedger.Entry> latest, Policy policy) {
        return latest.filter(e -> e.noticeVersion() == policy.noticeVersion())
                .map(ConsentLedger.Entry::noticeLanguage)
                .filter(policy.notices()::containsKey)
                .orElse(policy.defaultLanguage());
    }

    private static String noticeFor(Policy policy, String language) {
        return ConsentText.pick(policy.notices(), language, policy.defaultLanguage());
    }

    record Contact(String channel, String externalId) {}

    private Optional<Contact> contactForSession(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return jdbc
                .query(
                        "SELECT channel, external_id FROM channel_session WHERE session_id = ?",
                        (rs, n) -> new Contact(rs.getString(1), rs.getString(2)),
                        sessionId)
                .stream()
                .findFirst();
    }
}
