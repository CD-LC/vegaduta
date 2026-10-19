package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.common.privacy.ConsentGateService;
import com.agenticai.core.common.privacy.ConsentLedger;
import com.agenticai.core.common.privacy.ConsentPolicyService;
import com.agenticai.core.common.privacy.PurposeService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * DPDP consent and purpose controls against a real Postgres with two migrated tenant schemas -
 * see {@link DpdpControlsFixture} for the wiring and the sibling classes.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class DpdpControlsIT extends DpdpControlsFixture {

    @BeforeAll
    static void migrate() {
        migrateFreshDatabase("dpdp_it");
    }

    // ------------------------------------------------------------------------- consent

    @Test
    void contactIsHeldAtTheNoticeUntilTheyAgreeAndNothingIsStoredMeanwhile() {
        inTenant(A, () -> {
            enableConsent(agentA);

            assertThat(gate.mayStoreContent(agentA, CHANNEL, CONTACT)).isFalse();
            String first = reply(gate.intercept(agentA, sessionA, CONTACT, "hello, my name is Priya"));
            assertThat(first).contains(NOTICE_EN).contains("YES").contains("STOP");
            assertThat(gate.mayStoreContent(agentA, CHANNEL, CONTACT)).isFalse();

            // "ok" is not a clear affirmative action.
            assertThat(reply(gate.intercept(agentA, sessionA, CONTACT, "ok"))).contains(NOTICE_EN);
            assertThat(gate.mayStoreContent(agentA, CHANNEL, CONTACT)).isFalse();

            String thanks = reply(gate.intercept(agentA, sessionA, CONTACT, "YES"));
            assertThat(thanks).isEqualTo(ConsentGateService.THANKS);
            assertThat(gate.mayStoreContent(agentA, CHANNEL, CONTACT)).isTrue();
            // Consented: the turn proceeds to the agent.
            assertThat(gate.intercept(agentA, sessionA, CONTACT, "I need help with my order")).isNull();

            List<ConsentLedger.Entry> history = ledger.history(CHANNEL, CONTACT);
            assertThat(history).extracting(ConsentLedger.Entry::decision)
                    .containsExactly(ConsentLedger.Decision.PROMPTED, ConsentLedger.Decision.GRANTED);
            ConsentLedger.Entry granted = history.get(1);
            assertThat(granted.channel()).isEqualTo(CHANNEL);
            assertThat(granted.externalId()).isEqualTo(CONTACT);
            assertThat(granted.purpose()).isEqualTo("support");
            assertThat(granted.noticeVersion()).isEqualTo(1);
            assertThat(granted.noticeLanguage()).isEqualTo("en");
            assertThat(granted.noticeSha256()).hasSize(64);
            assertThat(granted.decidedAt()).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(5));
            verify(audit).record(eq("system:consent-gate"), eq("privacy.consent.granted"), anyString(), anyString(), eq(AuditOutcome.SUCCESS), anyString());
        });
    }

    @Test
    void contactCanReadTheNoticeInAnotherLanguageAndConsentRecordsThatLanguage() {
        inTenant(A, () -> {
            enableConsent(agentA);

            assertThat(reply(gate.intercept(agentA, sessionA, CONTACT, "LANG hi"))).contains(NOTICE_HI);
            gate.intercept(agentA, sessionA, CONTACT, "haan");

            ConsentLedger.Entry granted = ledger.latest(CHANNEL, CONTACT, "support").orElseThrow();
            assertThat(granted.decision()).isEqualTo(ConsentLedger.Decision.GRANTED);
            assertThat(granted.noticeLanguage()).isEqualTo("hi");
            assertThat(granted.noticeSha256()).isEqualTo(sha256Hex(NOTICE_HI));
        });
    }

    @Test
    void declineKeepsTheContactOutAndChangedNoticeAsksAgain() {
        inTenant(A, () -> {
            enableConsent(agentA);
            gate.intercept(agentA, sessionA, CONTACT, "hello");
            assertThat(reply(gate.intercept(agentA, sessionA, CONTACT, "no"))).isEqualTo(ConsentGateService.DECLINED);
            assertThat(gate.mayStoreContent(agentA, CHANNEL, CONTACT)).isFalse();

            gate.intercept(agentA, sessionA, CONTACT, "yes");
            assertThat(gate.mayStoreContent(agentA, CHANNEL, CONTACT)).isTrue();

            // The tenant edits the notice: version 2, the old agreement no longer counts.
            ConsentPolicyService.Policy p = policies.put(agentA, request(true, NOTICE_EN + " We also keep call logs."), "admin");
            assertThat(p.noticeVersion()).isEqualTo(2);
            assertThat(gate.mayStoreContent(agentA, CHANNEL, CONTACT)).isFalse();
            assertThat(reply(gate.intercept(agentA, sessionA, CONTACT, "hello again"))).contains("call logs");
            // Re-saving identical text does not bump the version.
            assertThat(policies.put(agentA, request(true, NOTICE_EN + " We also keep call logs."), "admin").noticeVersion())
                    .isEqualTo(2);
        });
    }

    @Test
    void withdrawingStopsProcessingAndErasesTheContactsData() {
        inTenant(A, () -> {
            enableConsent(agentA);
            gate.intercept(agentA, sessionA, CONTACT, "yes");
            assertThat(count("channel_message_log", CONTACT)).isEqualTo(4);

            String reply = reply(gate.intercept(agentA, sessionA, CONTACT, "STOP"));

            assertThat(reply).isEqualTo(ConsentGateService.WITHDRAWN_AND_ERASED);
            assertThat(count("channel_message_log", CONTACT)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM channel_session WHERE external_id = ?", Integer.class, CONTACT)).isZero();
            // The ledger is the proof; it keeps the withdrawal.
            assertThat(ledger.latest(CHANNEL, CONTACT, "support").orElseThrow().decision())
                    .isEqualTo(ConsentLedger.Decision.WITHDRAWN);
            assertThat(gate.mayStoreContent(agentA, CHANNEL, CONTACT)).isFalse();
        });
    }

    @Test
    void gateStaysOutOfTheWayForAgentsWithoutAPolicyAndForNonChannelSessions() {
        inTenant(A, () -> {
            assertThat(gate.intercept(agentA, sessionA, CONTACT, "hello")).isNull();
            assertThat(gate.mayStoreContent(agentA, CHANNEL, CONTACT)).isTrue();

            enableConsent(agentA);
            assertThat(gate.intercept(agentA, "playground-session-without-a-channel-row", "console-user", "hi")).isNull();
        });
    }

    @Test
    void consentInOneTenantNeverCountsInAnother() {
        inTenant(A, () -> {
            enableConsent(agentA);
            gate.intercept(agentA, sessionA, CONTACT, "yes");
            assertThat(gate.mayStoreContent(agentA, CHANNEL, CONTACT)).isTrue();
        });
        inTenant(B, () -> {
            enableConsent(agentB);
            // Same phone number, other tenant: starts unconsented.
            assertThat(gate.mayStoreContent(agentB, CHANNEL, CONTACT)).isFalse();
            assertThat(ledger.history(CHANNEL, CONTACT)).isEmpty();
        });
    }

    @Test
    void enablingConsentNeedsANoticeInTheDefaultLanguage() {
        inTenant(A, () -> {
            assertThatThrownBy(() -> policies.put(agentA,
                    new ConsentPolicyService.Request(true, "support", "en", Map.of("hi", NOTICE_HI), Map.of(), Map.of(), null), "admin"))
                    .isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> policies.put(UUID.randomUUID(), request(true, NOTICE_EN), "admin"))
                    .isInstanceOf(ResponseStatusException.class);
        });
    }

    // ------------------------------------------------------------------------- purposes

    @Test
    void aCollectionTaggedForOnePurposeIsNotUsableByAnAgentCarryingAnother() {
        inTenant(A, () -> {
            UUID support = collection("support-kb");
            UUID marketing = collection("marketing-kb");
            UUID untagged = collection("general-kb");
            purposes.replace(PurposeService.KNOWLEDGE_COLLECTION, support, List.of("support"), "admin");
            purposes.replace(PurposeService.KNOWLEDGE_COLLECTION, marketing, List.of("marketing"), "admin");
            Set<UUID> all = Set.of(support, marketing, untagged);

            // No tags on the agent yet: tagged collections are closed, untagged stays open.
            assertThat(purposes.permittedCollections(agentA, all)).containsExactly(untagged);

            purposes.replace(PurposeService.AGENT, agentA, List.of("support"), "admin");
            assertThat(purposes.permittedCollections(agentA, all)).containsExactlyInAnyOrder(support, untagged);

            // The purpose of an enabled consent policy counts as the agent's purpose too.
            UUID agent2 = insertAgent();
            policies.put(agent2, new ConsentPolicyService.Request(true, "marketing", "en", Map.of("en", "Marketing notice"), Map.of(), Map.of(), null), "admin");
            assertThat(purposes.permittedCollections(agent2, all)).containsExactlyInAnyOrder(marketing, untagged);

            // Clearing the tags reopens the collection.
            purposes.replace(PurposeService.KNOWLEDGE_COLLECTION, marketing, List.of(), "admin");
            assertThat(purposes.permittedCollections(agentA, all)).containsExactlyInAnyOrder(support, marketing, untagged);
            assertThat(purposes.permittedCollections(agentA, Set.of())).isEmpty();
        });
    }

    @Test
    void purposeTagsOfOneTenantDoNotLeakIntoAnother() {
        UUID shared = UUID.randomUUID();
        inTenant(A, () -> jdbc.update("INSERT INTO knowledge_collection (id, name, embedding_model) VALUES (?, 'kb', 'minilm-l6-v2')", shared));
        inTenant(B, () -> jdbc.update("INSERT INTO knowledge_collection (id, name, embedding_model) VALUES (?, 'kb', 'minilm-l6-v2')", shared));
        inTenant(A, () -> purposes.replace(PurposeService.KNOWLEDGE_COLLECTION, shared, List.of("marketing"), "admin"));
        // Same collection id in tenant B carries no tag there.
        inTenant(B, () -> assertThat(purposes.permittedCollections(agentB, Set.of(shared))).containsExactly(shared));
    }
}
