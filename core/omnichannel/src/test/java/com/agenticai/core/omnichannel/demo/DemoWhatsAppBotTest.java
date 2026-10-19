package com.agenticai.core.omnichannel.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.PlatformSettingsService;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class DemoWhatsAppBotTest {

    static final String SECRET = "app-secret-xyz";

    static DemoWhatsAppBot bot(
            DemoWhatsAppCloudApi meta,
            PlatformSettingsService settings,
            String pnid,
            String token,
            String secret,
            String number,
            String verify) {
        return new DemoWhatsAppBot(meta, settings, pnid, token, secret, number, verify, "");
    }

    static PlatformSettingsService settings(boolean enabled) {
        PlatformSettingsService s = mock(PlatformSettingsService.class);
        when(s.getBoolean("demo.whatsapp.enabled", true)).thenReturn(enabled);
        return s;
    }

    static DemoWhatsAppBot configured() {
        return bot(
                mock(DemoWhatsAppCloudApi.class),
                settings(true),
                "1234",
                "tok",
                SECRET,
                "+91 98765 43210",
                "vt");
    }

    static String sign(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void unavailableWhenAnySettingBlankOrKillSwitchOff() {
        assertFalse(
                bot(
                                mock(DemoWhatsAppCloudApi.class),
                                settings(true),
                                "",
                                "tok",
                                SECRET,
                                "+919876543210",
                                "vt")
                        .isAvailable());
        assertFalse(
                bot(
                                mock(DemoWhatsAppCloudApi.class),
                                settings(true),
                                "1",
                                "",
                                SECRET,
                                "+919876543210",
                                "vt")
                        .isAvailable());
        assertFalse(
                bot(
                                mock(DemoWhatsAppCloudApi.class),
                                settings(true),
                                "1",
                                "tok",
                                "",
                                "+919876543210",
                                "vt")
                        .isAvailable());
        assertFalse(
                bot(mock(DemoWhatsAppCloudApi.class), settings(true), "1", "tok", SECRET, "", "vt")
                        .isAvailable());
        assertFalse(
                bot(
                                mock(DemoWhatsAppCloudApi.class),
                                settings(true),
                                "1",
                                "tok",
                                SECRET,
                                "+919876543210",
                                "")
                        .isAvailable());
        DemoWhatsAppBot off =
                bot(
                        mock(DemoWhatsAppCloudApi.class),
                        settings(false),
                        "1",
                        "tok",
                        SECRET,
                        "+919876543210",
                        "vt");
        assertTrue(off.isConfigured());
        assertFalse(off.isAvailable());
        assertNull(off.deepLinkFor("VD-7F3K"));
        assertTrue(configured().isAvailable());
    }

    @Test
    void signatureGoodBadMissing() throws Exception {
        DemoWhatsAppBot bot = configured();
        byte[] body = "{\"entry\":[]}".getBytes(StandardCharsets.UTF_8);
        assertTrue(bot.verifySignature(body, sign(body, SECRET)));
        assertFalse(bot.verifySignature(body, sign(body, "other-secret")));
        assertFalse(
                bot.verifySignature(
                        body, sign("tampered".getBytes(StandardCharsets.UTF_8), SECRET)));
        assertFalse(bot.verifySignature(body, null));
        assertFalse(bot.verifySignature(body, ""));
        assertFalse(bot.verifySignature(body, "sha1=abc"));
        assertFalse(bot.verifySignature(null, sign(body, SECRET)));
    }

    @Test
    void deepLinkIsEncodedWaMe() {
        DemoWhatsAppBot bot = configured();
        assertEquals("https://wa.me/919876543210?text=VD-7F3K", bot.deepLinkFor("VD-7F3K"));
        assertEquals("https://wa.me/919876543210?text=a+b%26c", bot.deepLinkFor("a b&c"));
        assertNull(bot.deepLinkFor(null));
        assertNull(bot.deepLinkFor(" "));
    }

    @Test
    void maskedBusinessNumberDoesNotShowWholeNumber() {
        String masked = configured().businessNumberMasked();
        assertEquals("+91 ••••• ••210", masked);
        assertFalse(masked.contains("98765"));
    }

    @Test
    void hashedPhoneNeverContainsOrEqualsRawNumber() {
        DemoWhatsAppBot bot = configured();
        String raw = "919812345678";
        String h = bot.hashPhone(raw);
        assertNotEquals(raw, h);
        assertFalse(h.contains(raw));
        assertFalse(h.contains("9812345678"));
        assertEquals(64, h.length());
        // formatting-insensitive, stable, and distinct per number
        assertEquals(h, bot.hashPhone("+91 98123-45678"));
        assertNotEquals(h, bot.hashPhone("919812345679"));
        assertNull(bot.hashPhone(""));
        // keyed: a different key gives a different hash
        DemoWhatsAppBot other =
                bot(
                        mock(DemoWhatsAppCloudApi.class),
                        settings(true),
                        "1234",
                        "tok",
                        "different",
                        "+919876543210",
                        "vt");
        assertNotEquals(h, other.hashPhone(raw));
    }

    @Test
    void verifyTokenConstantTimeCompare() {
        DemoWhatsAppBot bot = configured();
        assertTrue(bot.verifyToken("vt"));
        assertFalse(bot.verifyToken("vT"));
        assertFalse(bot.verifyToken(null));
        assertFalse(
                bot(
                                mock(DemoWhatsAppCloudApi.class),
                                settings(true),
                                "1",
                                "t",
                                "s",
                                "+911234567890",
                                "")
                        .verifyToken(""));
    }

    @Test
    void sendTextUsesPlatformCredentialsAndSwallowsFailures() {
        DemoWhatsAppCloudApi meta = mock(DemoWhatsAppCloudApi.class);
        DemoWhatsAppBot bot =
                bot(meta, settings(true), "1234", "tok", SECRET, "+919876543210", "vt");
        assertTrue(bot.sendText("919812345678", "hello"));
        verify(meta).sendTextMessage("1234", "tok", "919812345678", "hello");
        when(meta.sendTextMessage(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("boom"));
        assertFalse(bot.sendText("919812345678", "hello"));
    }
}
