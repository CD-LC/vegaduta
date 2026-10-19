package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.secrets.SecretVaultService;
import com.agenticai.core.security.TenantContext;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code browser_totp}: the code reaches the model, the seed never does, and every failure is a
 * relayable JSON error.
 */
class BrowserTotpToolCallbackTest {

    private static final String SEED = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    private SecretVaultService vault;
    private BrowserTotpToolCallback callback;

    @BeforeEach
    void setUp() {
        vault = mock(SecretVaultService.class);
        // t = 59 s -> RFC 6238 SHA1 code 287082 with 1 s left in the step
        callback = new BrowserTotpToolCallback(vault, () -> 59L, "");
        TenantContext.set(TenantContext.of("tenant-1", "user-1", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void returnsTheCurrentCodeAndNeverTheSeed() {
        when(vault.resolveForRun("GITHUB_TOTP", "user-1")).thenReturn(Optional.of(SEED));

        String result = callback.call("{\"secretName\": \"github_totp\"}");

        assertThat(result)
                .contains("\"code\":\"287082\"")
                .contains("\"validForSeconds\":1")
                .contains("\"digits\":6");
        assertThat(result).doesNotContain(SEED);
        // resolved as the calling user, name upper-cased like the rest of the vault
        verify(vault).resolveForRun("GITHUB_TOTP", "user-1");
    }

    @Test
    void honoursAnOtpauthUriSeed() {
        when(vault.resolveForRun("X_TOTP", "user-1"))
                .thenReturn(Optional.of("otpauth://totp/x?secret=" + SEED + "&digits=8"));

        String result = callback.call("{\"secretName\": \"X_TOTP\"}");

        assertThat(result).contains("\"code\":\"94287082\"").contains("\"digits\":8");
    }

    @Test
    void reportsAMissingSecretWithoutLeakingAnything() {
        when(vault.resolveForRun(anyString(), anyString())).thenReturn(Optional.empty());

        String result = callback.call("{\"secretName\": \"NOPE\"}");

        assertThat(result).contains("error").contains("NOPE").doesNotContain("code");
    }

    @Test
    void aGarbageSeedIsAnErrorThatDoesNotEchoTheValue() {
        when(vault.resolveForRun("BAD_TOTP", "user-1"))
                .thenReturn(Optional.of("not*base32*at*all"));

        String result = callback.call("{\"secretName\": \"BAD_TOTP\"}");

        assertThat(result).contains("error").doesNotContain("not*base32");
    }

    @Test
    void aVaultFailureDoesNotEchoItsMessage() {
        when(vault.resolveForRun(eq("BOOM_TOTP"), anyString()))
                .thenThrow(new IllegalStateException("decrypt failed for " + SEED));

        String result = callback.call("{\"secretName\": \"BOOM_TOTP\"}");

        assertThat(result).contains("error").doesNotContain(SEED);
    }

    @Test
    void rejectsBadArgumentsBeforeTouchingTheVault() {
        assertThat(callback.call("{}")).contains("error").contains("secretName");
        assertThat(callback.call("{\"secretName\": \"has space\"}")).contains("error");
        assertThat(callback.call("{\"secretName\": \"1STARTS_WITH_DIGIT\"}")).contains("error");

        verify(vault, never()).resolveForRun(anyString(), anyString());
    }

    @Test
    void needsATenantContext() {
        TenantContext.clear();

        assertThat(callback.call("{\"secretName\": \"GITHUB_TOTP\"}"))
                .contains("error")
                .contains("tenant");
        verify(vault, never()).resolveForRun(anyString(), anyString());
    }

    @Test
    void onlySecretsNamedForBrowserUseCanBeUsedAsASeedWhenAPrefixIsConfigured() {
        BrowserTotpToolCallback prefixed =
                new BrowserTotpToolCallback(vault, () -> 59L, "browser_");
        when(vault.resolveForRun("BROWSER_GITHUB", "user-1")).thenReturn(Optional.of(SEED));

        assertThat(prefixed.call("{\"secretName\": \"GITHUB_API_KEY\"}"))
                .contains("error")
                .contains("BROWSER_");
        verify(vault, never()).resolveForRun(eq("GITHUB_API_KEY"), anyString());
        assertThat(prefixed.call("{\"secretName\": \"browser_github\"}"))
                .contains("\"code\":\"287082\"");
    }

    @Test
    void theDefinitionNamesTheToolAndRequiresTheSecretName() {
        assertThat(callback.getToolDefinition().name()).isEqualTo("browser_totp");
        assertThat(callback.getToolDefinition().inputSchema())
                .contains("secretName")
                .contains("required");
    }
}
