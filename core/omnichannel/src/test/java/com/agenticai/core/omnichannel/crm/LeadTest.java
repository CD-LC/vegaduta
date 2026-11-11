package com.agenticai.core.omnichannel.crm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link Lead} and {@link LeadStage}: the CRM pipeline entity and its lenient stage parser. */
class LeadTest {

    @Test
    void constructorNormalizesEmailAndStartsAtNew() {
        Lead lead = new Lead("Asha", "  Asha@Example.COM ");

        assertEquals("Asha", lead.getName());
        assertEquals("asha@example.com", lead.getEmail());
        assertEquals(LeadStage.NEW, lead.getStage());
        assertEquals(0, lead.getScore());
        assertNotNull(lead.getCreatedAt());
        assertNotNull(lead.getUpdatedAt());
        assertNull(lead.getId());
    }

    @Test
    void normalizeEmailMapsBlankInputToNull() {
        assertNull(Lead.normalizeEmail(null));
        assertNull(Lead.normalizeEmail("   "));
        assertEquals("a@b.c", Lead.normalizeEmail(" A@B.C "));
    }

    @Test
    void setEmailNormalizesToo() {
        Lead lead = new Lead("Asha", null);
        lead.setEmail(" X@Y.Z ");
        assertEquals("x@y.z", lead.getEmail());
        lead.setEmail(" ");
        assertNull(lead.getEmail());
    }

    @Test
    void joinTagsCleansDedupesAndJoins() {
        assertNull(Lead.joinTags(null));
        assertNull(Lead.joinTags(List.of(" ", "")));
        assertEquals("a,b", Lead.joinTags(java.util.Arrays.asList(" a ", "b", "a", null)));
        assertEquals("one two,three", Lead.joinTags(List.of("one,two", "three")));
    }

    @Test
    void tagListSplitsAndTrims() {
        Lead lead = new Lead("Asha", null);
        assertTrue(lead.tagList().isEmpty());
        lead.setTags(" ");
        assertTrue(lead.tagList().isEmpty());
        lead.setTags(" a , b ,, c ");
        assertEquals(List.of("a", "b", "c"), lead.tagList());
    }

    @Test
    void setScoreClampsIntoRange() {
        Lead lead = new Lead("Asha", null);
        lead.setScore(150);
        assertEquals(100, lead.getScore());
        lead.setScore(-10);
        assertEquals(0, lead.getScore());
        lead.setScore(42);
        assertEquals(42, lead.getScore());
    }

    @Test
    void plainSettersRoundTrip() {
        Lead lead = new Lead("Asha", null);
        Instant contacted = Instant.parse("2026-01-01T00:00:00Z");
        Instant replied = Instant.parse("2026-01-02T00:00:00Z");

        lead.setPhone("+1555");
        lead.setCompany("Acme");
        lead.setTitle("CTO");
        lead.setSource("whatsapp");
        lead.setStage(LeadStage.QUALIFIED);
        lead.setOwner("admin@x.test");
        lead.setValue(new BigDecimal("12.50"));
        lead.setTags("vip");
        lead.setNotes("met at conf");
        lead.setLastContactedAt(contacted);
        lead.setLastRepliedAt(replied);

        assertEquals("+1555", lead.getPhone());
        assertEquals("Acme", lead.getCompany());
        assertEquals("CTO", lead.getTitle());
        assertEquals("whatsapp", lead.getSource());
        assertEquals(LeadStage.QUALIFIED, lead.getStage());
        assertEquals("admin@x.test", lead.getOwner());
        assertEquals(new BigDecimal("12.50"), lead.getValue());
        assertEquals("vip", lead.getTags());
        assertEquals("met at conf", lead.getNotes());
        assertEquals(contacted, lead.getLastContactedAt());
        assertEquals(replied, lead.getLastRepliedAt());
    }

    @Test
    void touchRefreshesUpdatedAt() {
        Lead lead = new Lead("Asha", null);
        Instant before = lead.getUpdatedAt();
        lead.touch();
        assertTrue(!lead.getUpdatedAt().isBefore(before));
    }

    @Test
    void leadStageParseIsLenient() {
        assertNull(LeadStage.parse(null));
        assertNull(LeadStage.parse("  "));
        assertEquals(LeadStage.QUALIFIED, LeadStage.parse("qualified"));
        assertEquals(LeadStage.WON, LeadStage.parse(" Won "));
        assertThrows(IllegalArgumentException.class, () -> LeadStage.parse("nonsense"));
    }
}
