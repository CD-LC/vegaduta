package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.providers.PlatformHostedLlmProviderService.HostedProviderSelection;
import com.agenticai.core.security.net.SsrfGuard;
import java.net.InetAddress;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The read side of the hosted LLM pool: a row is in the chain only when the pool's master switch is
 * on, the row is enabled, its stored base URL still passes the SSRF re-check, and its key decrypts.
 * Every failure degrades to "not offered" (logged once per row) rather than an error - one bad row
 * must never fail every FREE-tier chat.
 */
class HostedPoolChainResolverTest {

    private PlatformHostedLlmProviderRepository repository;
    private EncryptionService encryptionService;
    private AtomicBoolean poolEnabled;
    private HostedPoolChainResolver resolver;

    /** Public-name resolution without real DNS for the per-request re-check. */
    private static SsrfGuard publicGuard() {
        return SsrfGuard.of(
                Set.of(),
                host ->
                        "169.254.169.254".equals(host)
                                ? new InetAddress[] {InetAddress.getByName("169.254.169.254")}
                                : new InetAddress[] {InetAddress.getByName("93.184.216.34")});
    }

    private static PlatformHostedLlmProvider row(String baseUrl, String encryptedKey) {
        return new PlatformHostedLlmProvider(
                "Row " + baseUrl, baseUrl, "model-1", encryptedKey, true, 0, null, null);
    }

    @BeforeEach
    void setUp() {
        repository = mock(PlatformHostedLlmProviderRepository.class);
        encryptionService = mock(EncryptionService.class);
        poolEnabled = new AtomicBoolean(true);
        resolver =
                new HostedPoolChainResolver(
                        repository,
                        encryptionService,
                        poolEnabled::get,
                        HostedPoolChainResolverTest::publicGuard);
    }

    @Test
    void theMasterSwitchOffShortCircuitsEverything() {
        poolEnabled.set(false);
        assertEquals(List.of(), resolver.enabledChain());
        assertEquals(Optional.empty(), resolver.enabledSelectionById(7L));
    }

    @Test
    void aHealthyRowYieldsASelectionWithItsDecryptedKey() {
        PlatformHostedLlmProvider provider = row("https://api.groq.com/openai", "cipher");
        when(repository.findAllByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(provider));
        when(encryptionService.decrypt("cipher")).thenReturn("plain-key");

        List<HostedProviderSelection> chain = resolver.enabledChain();
        assertEquals(1, chain.size());
        assertEquals(provider, chain.get(0).provider());
        assertEquals("plain-key", chain.get(0).decryptedApiKey());
    }

    @Test
    void aRowWithANullStoredKeySelectsWithANullDecryptedKey() {
        // A VERTEX_ADC row stores no secret at all (V169) - decrypt must not
        // even be called on null.
        PlatformHostedLlmProvider provider = row("https://vertex.example", null);
        when(repository.findAllByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(provider));

        List<HostedProviderSelection> chain = resolver.enabledChain();
        assertEquals(1, chain.size());
        assertNull(chain.get(0).decryptedApiKey());
    }

    @Test
    void aRowWhoseKeyCannotBeDecryptedIsSkippedNotFatal() {
        PlatformHostedLlmProvider bad = row("https://bad.example", "corrupt");
        PlatformHostedLlmProvider good = row("https://good.example", "cipher");
        when(repository.findAllByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(bad, good));
        when(encryptionService.decrypt("corrupt"))
                .thenThrow(new IllegalStateException("bad padding"));
        when(encryptionService.decrypt("cipher")).thenReturn("plain");

        List<HostedProviderSelection> chain = resolver.enabledChain();
        assertEquals(1, chain.size());
        assertEquals(good, chain.get(0).provider());

        // The failure is reported once per row: a second resolve of the same
        // still-broken row stays quiet but keeps skipping it.
        assertEquals(1, resolver.enabledChain().size());
    }

    @Test
    void aRowWhoseBaseUrlFailsTheSsrfRecheckIsSkipped() {
        // An IP literal is classified without DNS: a private address fails the
        // no-DNS re-check even when the resolver would claim a public address.
        PlatformHostedLlmProvider internal = row("http://169.254.169.254", "cipher");
        PlatformHostedLlmProvider good = row("https://api.groq.com/openai", "cipher");
        when(repository.findAllByEnabledTrueOrderByPriorityAsc())
                .thenReturn(List.of(internal, good));
        when(encryptionService.decrypt(anyString())).thenReturn("plain");

        List<HostedProviderSelection> chain = resolver.enabledChain();
        assertEquals(1, chain.size());
        assertEquals(good, chain.get(0).provider());
    }

    @Test
    void enabledSelectionByIdChecksEnabledFlagUrlAndKey() {
        PlatformHostedLlmProvider provider = row("https://api.groq.com/openai", "cipher");
        when(repository.findById(7L)).thenReturn(Optional.of(provider));
        when(encryptionService.decrypt("cipher")).thenReturn("plain");

        assertTrue(resolver.enabledSelectionById(7L).isPresent());
        assertEquals(Optional.empty(), resolver.enabledSelectionById(8L));

        provider.apply("l", "https://api.groq.com/openai", "m", null, false, 0, null, null);
        assertEquals(
                Optional.empty(),
                resolver.enabledSelectionById(7L),
                "a disabled row does not select even when the pool is on");
        assertTrue(
                resolver.enabledSelectionByIdIgnoringPoolSwitch(7L).isEmpty(),
                "disabled stays disabled under the ignoring-pool-switch variant too");
    }

    @Test
    void ignoringPoolSwitchSelectsAnEnabledRowWhileThePoolIsOff() {
        poolEnabled.set(false);
        PlatformHostedLlmProvider provider = row("https://api.groq.com/openai", "cipher");
        when(repository.findById(7L)).thenReturn(Optional.of(provider));
        when(encryptionService.decrypt("cipher")).thenReturn("plain");

        assertTrue(resolver.enabledSelectionByIdIgnoringPoolSwitch(7L).isPresent());
        assertEquals(Optional.empty(), resolver.enabledSelectionById(7L));
    }
}
