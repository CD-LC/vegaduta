package com.agenticai.core.omnichannel.whatsapp.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.omnichannel.whatsapp.WhatsAppSendBlockedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WhatsAppSendGuardTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");

    private final Map<String, WhatsAppOptIn> optIns = new HashMap<>();
    private final Map<String, WhatsAppRecipientBackoff> backoffs = new HashMap<>();
    private Instant now;
    private WhatsAppSendGuard guard;

    @BeforeEach
    void setUp() {
        now = T0;
        WhatsAppOptInRepository optRepo = Mockito.mock(WhatsAppOptInRepository.class);
        Mockito.when(optRepo.findByPhoneDigits(Mockito.anyString()))
                .thenAnswer(i -> Optional.ofNullable(optIns.get(i.<String>getArgument(0))));
        Mockito.when(optRepo.save(Mockito.any(WhatsAppOptIn.class)))
                .thenAnswer(
                        i -> {
                            WhatsAppOptIn o = i.getArgument(0);
                            optIns.put(o.getPhoneDigits(), o);
                            return o;
                        });
        WhatsAppRecipientBackoffRepository boRepo =
                Mockito.mock(WhatsAppRecipientBackoffRepository.class);
        Mockito.when(boRepo.findById(Mockito.anyString()))
                .thenAnswer(i -> Optional.ofNullable(backoffs.get(i.<String>getArgument(0))));
        Mockito.when(boRepo.save(Mockito.any(WhatsAppRecipientBackoff.class)))
                .thenAnswer(
                        i -> {
                            WhatsAppRecipientBackoff b = i.getArgument(0);
                            backoffs.put(b.getPhoneDigits(), b);
                            return b;
                        });
        Mockito.doAnswer(
                        i -> {
                            backoffs.remove(i.<WhatsAppRecipientBackoff>getArgument(0).getPhoneDigits());
                            return null;
                        })
                .when(boRepo)
                .delete(Mockito.any(WhatsAppRecipientBackoff.class));
        Clock clock =
                new Clock() {
                    public java.time.ZoneId getZone() {
                        return ZoneOffset.UTC;
                    }

                    public Clock withZone(java.time.ZoneId z) {
                        return this;
                    }

                    public Instant instant() {
                        return now;
                    }
                };
        guard = new WhatsAppSendGuard(optRepo, boRepo, clock);
    }

    @Test
    void sendWithoutOptInIsBlocked() {
        var e =
                assertThrows(
                        WhatsAppSendBlockedException.class,
                        () -> guard.assertMaySendTemplate("+1 555 123 4567", () -> true));
        assertTrue(e.getMessage().contains("opt-in"));
    }

    @Test
    void sendWithOptInPassesAndFormattingDoesNotMatter() {
        guard.recordOptIn("+1 (555) 123-4567", "web_form", "checkout box", "admin");
        assertDoesNotThrow(() -> guard.assertMaySendTemplate("15551234567", () -> true));
    }

    @Test
    void revokedOptInBlocksAgain() {
        guard.recordOptIn("15551234567", "web_form", null, "admin");
        assertTrue(guard.revokeOptIn("+15551234567"));
        assertThrows(
                WhatsAppSendBlockedException.class,
                () -> guard.assertMaySendTemplate("15551234567", () -> false));
        assertFalse(guard.revokeOptIn("not a number"));
    }

    @Test
    void marketingLimit131049BacksOffMarketingFor24HoursOnly() {
        guard.recordOptIn("15551234567", "web_form", null, "admin");
        guard.recordRejection("15551234567", 131049);

        assertThrows(
                WhatsAppSendBlockedException.class,
                () -> guard.assertMaySendTemplate("15551234567", () -> true));
        assertDoesNotThrow(
                () -> guard.assertMaySendTemplate("15551234567", () -> false),
                "utility templates are not subject to the marketing cap");

        now = T0.plus(Duration.ofHours(23));
        assertThrows(
                WhatsAppSendBlockedException.class,
                () -> guard.assertMaySendTemplate("15551234567", () -> true));
        now = T0.plus(Duration.ofHours(24)).plusSeconds(1);
        assertDoesNotThrow(() -> guard.assertMaySendTemplate("15551234567", () -> true));
    }

    @Test
    void stoppedMarketing131050IsPermanentUntilFreshOptIn() {
        guard.recordOptIn("15551234567", "web_form", null, "admin");
        guard.recordRejection("15551234567", 131050);
        now = T0.plus(Duration.ofDays(400));
        assertThrows(
                WhatsAppSendBlockedException.class,
                () -> guard.assertMaySendTemplate("15551234567", () -> true));

        guard.recordOptIn("15551234567", "re_consent", "user texted START", "admin");
        assertDoesNotThrow(() -> guard.assertMaySendTemplate("15551234567", () -> true));
    }

    @Test
    void otherMetaCodesDoNotCreateBackoff() {
        guard.recordOptIn("15551234567", "web_form", null, "admin");
        guard.recordRejection("15551234567", 130429);
        guard.recordRejection("15551234567", 0);
        assertEquals(0, backoffs.size());
        assertDoesNotThrow(() -> guard.assertMaySendTemplate("15551234567", () -> true));
    }

    @Test
    void marketingLookupIsSkippedWhenThereIsNoBackoff() {
        guard.recordOptIn("15551234567", "web_form", null, "admin");
        assertDoesNotThrow(
                () ->
                        guard.assertMaySendTemplate(
                                "15551234567",
                                () -> {
                                    throw new AssertionError("must not be evaluated");
                                }));
    }
}
