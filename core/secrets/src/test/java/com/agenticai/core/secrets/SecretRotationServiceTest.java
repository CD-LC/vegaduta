package com.agenticai.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.crypto.EncryptionService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Key rotation. The failure mode this guards against is subtle and expensive:
 * a rotation that reports success while some rows still need the previous key,
 * followed months later by someone removing that key.
 */
class SecretRotationServiceTest {

    private TenantSecretRepository repository;
    private EncryptionService encryptionService;
    private AuditLogService auditLogService;
    private SecretRotationService service;

    @BeforeEach
    void setUp() {
        repository = mock(TenantSecretRepository.class);
        encryptionService = mock(EncryptionService.class);
        auditLogService = mock(AuditLogService.class);
        service = new SecretRotationService(repository, encryptionService, auditLogService);
    }

    private TenantSecret secret(String name, String ciphertext) {
        return new TenantSecret(name, SecretScope.TENANT, null, SecretKind.API_KEY,
            null, ciphertext, "alue", null, false, "admin");
    }

    @Test
    void rewrapsEveryRowUnderTheCurrentKey() {
        TenantSecret a = secret("A_KEY", "old:1");
        TenantSecret b = secret("B_KEY", "old:2");
        when(repository.findAll()).thenReturn(List.of(a, b));
        when(encryptionService.reencrypt(anyString())).thenAnswer(i -> "new:" + i.getArgument(0));

        SecretRotationService.RotationResult result = service.rotateAll("admin");

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.rotated()).isEqualTo(2);
        assertThat(result.isClean()).isTrue();
        assertThat(a.encryptedValue()).isEqualTo("new:old:1");
    }

    @Test
    void aRowThatCannotBeDecryptedIsLeftUNTOUCHED_notOverwritten() {
        TenantSecret ok = secret("A_KEY", "old:1");
        TenantSecret broken = secret("B_KEY", "unreadable");
        when(repository.findAll()).thenReturn(List.of(ok, broken));
        when(encryptionService.reencrypt("old:1")).thenReturn("new:old:1");
        when(encryptionService.reencrypt("unreadable")).thenThrow(new IllegalStateException("bad key"));

        SecretRotationService.RotationResult result = service.rotateAll("admin");

        assertThat(result.rotated()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.isClean()).isFalse();
        // The whole point: a correct previous key could still recover this row.
        // Overwriting it with anything would destroy the secret permanently.
        assertThat(broken.encryptedValue()).isEqualTo("unreadable");
    }

    @Test
    void anIncompleteRotationIsAuditedAsAFailureSoItCannotLookDone() {
        when(repository.findAll()).thenReturn(List.of(secret("A_KEY", "x")));
        when(encryptionService.reencrypt(anyString())).thenThrow(new IllegalStateException("bad key"));

        service.rotateAll("admin");

        verify(auditLogService).record(
            eq("admin"), eq("SECRET_KEY_ROTATED"), eq("SECRET"), isNull(),
            eq(AuditOutcome.FAILURE), contains("FAILED"));
    }

    @Test
    void rewrappingDoesNotLookLikeACredentialRotationInTheUi() {
        TenantSecret s = secret("A_KEY", "old:1");
        when(repository.findAll()).thenReturn(List.of(s));
        when(encryptionService.reencrypt(anyString())).thenReturn("new:1");

        service.rotateAll("admin");

        // The VALUE did not change - only how it is wrapped. Stamping these
        // would send people hunting for a credential change that never happened.
        assertThat(s.getRotatedAt()).isNull();
        assertThat(s.getValuePreview()).isEqualTo("alue");
    }

    @Test
    void anEmptyVaultIsACleanNoOp() {
        when(repository.findAll()).thenReturn(List.of());

        SecretRotationService.RotationResult result = service.rotateAll("admin");

        assertThat(result.total()).isZero();
        assertThat(result.isClean()).isTrue();
        verify(auditLogService).record(anyString(), anyString(), anyString(), any(), eq(AuditOutcome.SUCCESS), anyString());
    }
}
