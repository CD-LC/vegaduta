package com.agenticai.core.chat.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class LlmGatewayContentPolicyControllerTest {

    private final LlmGatewayContentPolicyRepository repository = mock(LlmGatewayContentPolicyRepository.class);
    private final LlmGatewayContentPolicyService service = new LlmGatewayContentPolicyService(repository);
    private final LlmGatewayContentPolicyController controller = new LlmGatewayContentPolicyController(service);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private static void asTenant(String tenantId) {
        TenantContext.set(TenantContext.of(tenantId, "user-1", Set.of()));
    }

    private static LlmGatewayContentPolicyController.ContentPolicyRequest request(
        Boolean pii, Boolean injection, String action
    ) {
        return new LlmGatewayContentPolicyController.ContentPolicyRequest(pii, true, false, true, true, injection, action);
    }

    @Test
    void getReturnsUnconfiguredDefaultsWhenNoRow() {
        asTenant("tenant-a");
        when(repository.findByTenantId("tenant-a")).thenReturn(Optional.empty());

        LlmGatewayContentPolicyController.ContentPolicyResponse response = controller.get();

        // Owner decision 2026-09-28: injection screening defaults ON (FLAG), PII redaction stays opt-in.
        assertFalse(response.piiRedactionEnabled());
        assertTrue(response.injectionScreeningEnabled());
        assertEquals("FLAG", response.injectionScreeningAction());
        assertTrue(response.piiRedactEmail());
    }

    @Test
    void getFailsOpenToNonBlockingDefaultWhenLookupThrows() {
        asTenant("tenant-a");
        when(repository.findByTenantId("tenant-a")).thenThrow(new IllegalStateException("db down"));

        LlmGatewayContentPolicyController.ContentPolicyResponse response = controller.get();

        assertFalse(response.piiRedactionEnabled());
        assertTrue(response.injectionScreeningEnabled());
        assertEquals("FLAG", response.injectionScreeningAction());
    }

    @Test
    void storedOptOutIsHonouredNotReinterpreted() {
        asTenant("tenant-a");
        LlmGatewayContentPolicy optedOut = new LlmGatewayContentPolicy("tenant-a");
        optedOut.setInjectionScreeningEnabled(false);
        when(repository.findByTenantId("tenant-a")).thenReturn(Optional.of(optedOut));

        assertFalse(controller.get().injectionScreeningEnabled());
    }

    @Test
    void getWithoutTenantContextFails() {
        assertThrows(IllegalStateException.class, controller::get);
    }

    @Test
    void putCreatesRowForCurrentTenantAndReturnsIt() {
        asTenant("tenant-a");
        when(repository.findByTenantId("tenant-a")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        LlmGatewayContentPolicyController.ContentPolicyResponse response =
            controller.update(request(true, true, "block"));

        ArgumentCaptor<LlmGatewayContentPolicy> saved = ArgumentCaptor.forClass(LlmGatewayContentPolicy.class);
        verify(repository).save(saved.capture());
        assertEquals("tenant-a", saved.getValue().getTenantId());
        assertEquals("user-1", saved.getValue().getCreatedBy());
        assertTrue(saved.getValue().isPiiRedactionEnabled());
        assertFalse(saved.getValue().isPiiRedactPhone());
        assertEquals(LlmGatewayContentPolicyAction.BLOCK, saved.getValue().getInjectionScreeningAction());
        assertEquals("BLOCK", response.injectionScreeningAction());
        assertFalse(response.piiRedactPhone());
    }

    @Test
    void putUpdatesExistingRowInPlace() {
        asTenant("tenant-a");
        LlmGatewayContentPolicy existing = new LlmGatewayContentPolicy("tenant-a");
        existing.setCreatedBy("original");
        when(repository.findByTenantId("tenant-a")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        controller.update(request(false, false, "FLAG"));

        verify(repository).save(existing);
        assertEquals("original", existing.getCreatedBy());
        assertFalse(existing.isPiiRedactionEnabled());
        assertFalse(existing.isInjectionScreeningEnabled());
    }

    @Test
    void putRejectsMissingFieldsAndBadActionWithoutSaving() {
        asTenant("tenant-a");

        for (LlmGatewayContentPolicyController.ContentPolicyRequest bad : new LlmGatewayContentPolicyController.ContentPolicyRequest[] {
            request(null, true, "FLAG"),
            request(true, null, "FLAG"),
            request(true, true, null),
            request(true, true, "  "),
            request(true, true, "DROP"),
            null,
        }) {
            ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> controller.update(bad));
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        }
        verify(repository, never()).save(any());
    }

    @Test
    void putWithoutTenantContextFailsBeforeAnyWrite() {
        assertThrows(IllegalStateException.class, () -> controller.update(request(true, true, "FLAG")));
        verify(repository, never()).save(any());
    }

    @Test
    void serviceUpsertRejectsBlankTenantAndNullAction() {
        LlmGatewayContentPolicyService.PolicyUpdate ok =
            new LlmGatewayContentPolicyService.PolicyUpdate(true, true, true, true, true, true, LlmGatewayContentPolicyAction.FLAG);
        LlmGatewayContentPolicyService.PolicyUpdate noAction =
            new LlmGatewayContentPolicyService.PolicyUpdate(true, true, true, true, true, true, null);

        assertThrows(IllegalArgumentException.class, () -> service.upsert(" ", ok, "u"));
        assertThrows(IllegalArgumentException.class, () -> service.upsert(null, ok, "u"));
        assertThrows(IllegalArgumentException.class, () -> service.upsert("t", null, "u"));
        assertThrows(IllegalArgumentException.class, () -> service.upsert("t", noAction, "u"));
        verify(repository, never()).save(any());
        verify(repository, never()).findByTenantId(eq("t"));
    }
}
