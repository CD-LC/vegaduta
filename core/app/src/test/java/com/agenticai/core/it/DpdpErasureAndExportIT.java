package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.common.privacy.ConsentLedger;
import com.agenticai.core.common.privacy.ContactPrivacyService;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * DPDP data-subject erasure and export, and the breach-support audit export. See {@link
 * DpdpControlsFixture}.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class DpdpErasureAndExportIT extends DpdpControlsFixture {

    @BeforeAll
    static void migrate() {
        migrateFreshDatabase("dpdp_erasure_it");
    }

    // ------------------------------------------------------------------------- erase and export

    @Test
    void erasureRemovesTheContactsMessagesMemoryAndDerivedKnowledgeButNotOtherPeoplesOrOtherTenants() {
        inTenant(A, () -> {
            seedContactData(agentA, sessionA);
            when(graph.eraseSubject(CONTACT)).thenReturn(3);
            when(sessionMemory.rewind(sessionA, 0)).thenReturn(2);
            ledger.record(CHANNEL, CONTACT, agentA, "support", 1, "en", "h", ConsentLedger.Decision.GRANTED);

            ContactPrivacyService.ErasureReport report =
                    contacts.erase(CHANNEL, CONTACT, new ContactPrivacyService.EraseOptions(false, false), "admin");

            assertThat(report.complete()).isTrue();
            assertThat(report.erased()).containsEntry("messages", 4L)
                    .containsEntry("derivedKnowledgeChunks", 1L)
                    .containsEntry("agentMemory", 1L)
                    .containsEntry("sharedMemory", 1L)
                    .containsEntry("graphMemoryEntities", 3L)
                    .containsEntry("liveConversationWindow", 2L)
                    .containsEntry("contactRecords", 1L)
                    .containsEntry("consentRecords", 1L)
                    .containsEntry("sessions", 1L);
            verify(graph).eraseSubject(CONTACT);

            // Derived data really is gone...
            assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_chunks WHERE source LIKE ?", Integer.class, "%session " + sessionA + ",%")).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_memory WHERE subject_key = ?", Integer.class, CONTACT)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_memory WHERE subject_key = ?", Integer.class, CONTACT)).isZero();
            // ...while another person's data and an uploaded document's chunk stay.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_chunks WHERE source = 'handbook.pdf'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_chunks WHERE source LIKE '%session other-session,%'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_memory WHERE subject_key = 'someone-else'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM lead", Integer.class)).isEqualTo(1);

            // The response is honest about what it did not reach.
            assertThat(report.notReached()).anyMatch(s -> s.contains("backups")).anyMatch(s -> s.contains("third parties") || s.contains("Copies held by third parties"));
            assertThat(report.subjectMasked()).isEqualTo("***3210");
            verify(audit).record(eq("admin"), eq("privacy.contact.erase"), anyString(), eq("***3210"), eq(AuditOutcome.SUCCESS), anyString());
        });
        // Tenant B has a contact with the same number: untouched.
        inTenant(B, () -> {
            assertThat(count("channel_message_log", CONTACT)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM channel_session WHERE external_id = ?", Integer.class, CONTACT)).isEqualTo(1);
        });
    }

    @Test
    void erasureCanKeepTheConsentProofAndCanIncludeCrmLeads() {
        inTenant(A, () -> {
            seedContactData(agentA, sessionA);
            ledger.record(CHANNEL, CONTACT, agentA, "support", 1, "en", "h", ConsentLedger.Decision.GRANTED);

            ContactPrivacyService.ErasureReport report =
                    contacts.erase(CHANNEL, CONTACT, new ContactPrivacyService.EraseOptions(true, true), "admin");

            assertThat(report.consentProofKept()).isTrue();
            assertThat(report.erased()).containsEntry("crmLeads", 1L).doesNotContainKey("consentRecords");
            assertThat(ledger.history(CHANNEL, CONTACT)).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM lead", Integer.class)).isZero();
        });
    }

    @Test
    void anErasureThatFailsPartwayIsReportedIncompleteAndLeavesTheSessionForARetry() {
        inTenant(A, () -> {
            seedContactData(agentA, sessionA);
            when(graph.eraseSubject(CONTACT)).thenThrow(new IllegalStateException("age down"));

            ContactPrivacyService.ErasureReport report =
                    contacts.erase(CHANNEL, CONTACT, new ContactPrivacyService.EraseOptions(false, false), "admin");

            assertThat(report.complete()).isFalse();
            assertThat(report.failures()).anyMatch(f -> f.startsWith("graphMemoryEntities"));
            // Everything that could be erased was; the session row remains so a retry can find it.
            assertThat(count("channel_message_log", CONTACT)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM channel_session WHERE external_id = ?", Integer.class, CONTACT)).isEqualTo(1);
            verify(audit).record(eq("admin"), eq("privacy.contact.erase"), anyString(), anyString(), eq(AuditOutcome.FAILURE), anyString());
        });
    }

    @Test
    void exportListsTheContactsDataAndWhatItDoesNotInclude() {
        inTenant(A, () -> {
            seedContactData(agentA, sessionA);
            ledger.record(CHANNEL, CONTACT, agentA, "support", 1, "en", "h", ConsentLedger.Decision.GRANTED);

            ContactPrivacyService.ExportReport report = contacts.export(CHANNEL, CONTACT, false, "admin");

            assertThat((List<?>) report.data().get("messages")).hasSize(4);
            assertThat((List<?>) report.data().get("agentMemory")).hasSize(1);
            assertThat((List<?>) report.data().get("sharedMemory")).hasSize(1);
            assertThat((List<?>) report.data().get("derivedKnowledgeChunks")).hasSize(1);
            assertThat((List<?>) report.data().get("consentHistory")).hasSize(1);
            assertThat(report.data()).doesNotContainKey("crmLeads");
            assertThat(report.notIncluded()).anyMatch(s -> s.contains("CRM leads")).anyMatch(s -> s.contains("backups"));
            // Export is read-only.
            assertThat(count("channel_message_log", CONTACT)).isEqualTo(4);
        });
    }

    // ------------------------------------------------------------------------- breach support

    @Test
    void auditExportContainsOnlyTheCallersTenantAndTheRequestedRange() throws Exception {
        Instant t0 = Instant.parse("2026-06-01T00:00:00Z");
        inTenant(A, () -> {
            insertAudit("a-in-1", t0.plusSeconds(60), "login,with comma");
            insertAudit("a-in-2", t0.plusSeconds(120), "=cmd|' /C calc'!A0");
            insertAudit("a-out", t0.plus(Duration.ofDays(10)), "after range");
        });
        inTenant(B, () -> insertAudit("b-in", t0.plusSeconds(90), "other tenant"));

        inTenant(A, () -> {
            ByteArrayOutputStream csv = new ByteArrayOutputStream();
            long rows = exportCsv(csv, t0, t0.plus(Duration.ofDays(1)));
            String text = csv.toString(StandardCharsets.UTF_8);
            assertThat(rows).isEqualTo(2);
            assertThat(text).contains("a-in-1").contains("a-in-2").doesNotContain("b-in").doesNotContain("a-out");
            assertThat(text).startsWith("id,created_at,actor_id,action_type");
            assertThat(text).contains("\"login,with comma\"").contains("'=cmd");

            ByteArrayOutputStream json = new ByteArrayOutputStream();
            long jsonRows = exportJson(json, t0, t0.plus(Duration.ofDays(1)));
            assertThat(jsonRows).isEqualTo(2);
            assertThat(json.toString(StandardCharsets.UTF_8)).contains("\"entries\"").contains("\"seals\"").doesNotContain("b-in");
            verify(audit, atLeastOnce()).record(eq("admin"), eq("privacy.audit.export"), anyString(), anyString(), eq(AuditOutcome.SUCCESS), anyString());
        });
        inTenant(B, () -> {
            ByteArrayOutputStream csv = new ByteArrayOutputStream();
            exportCsv(csv, t0, t0.plus(Duration.ofDays(1)));
            assertThat(csv.toString(StandardCharsets.UTF_8)).contains("b-in").doesNotContain("a-in-1");
        });
    }
}
