package com.agenticai.core.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.templates.dto.TemplateResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Lifecycle-transition, public-visibility and admin-enforcement (incl. SEC-010) tests for {@link
 * CommunityTemplateService}. Import is in {@link CommunityTemplateImportTest}; ratings and the
 * verified badge in {@link CommunityTemplateMarketplaceTest}.
 */
class CommunityTemplateServiceTest extends CommunityTemplateServiceTestBase {

    // ------------------------------- lifecycle -------------------------------

    @Test
    void submit_movesDraftToPending_andAudits() {
        asMember(OWNER);
        CommunityTemplate draft = draft(OWNER, TemplateStatus.DRAFT);
        when(repository.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TemplateResponse response = service.submit(draft.getId());

        assertThat(response.status()).isEqualTo(TemplateStatus.PENDING);
        verify(auditLogService)
                .record(eq(OWNER), eq("template.submit"), eq("template"), any(), any(), any());
    }

    @Test
    void approve_onADraftNeverSubmitted_isRejectedAsIllegalTransition() {
        asAdmin(ADMIN);
        CommunityTemplate draft = draft(OWNER, TemplateStatus.DRAFT);
        when(repository.findById(draft.getId())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.approve(draft.getId()))
                .isInstanceOf(IllegalTemplateTransitionException.class);
        verify(auditLogService, never())
                .record(any(), eq("template.approve"), any(), any(), any(), any());
    }

    @Test
    void fullHappyPath_submitThenApprove_reachesApproved() {
        CommunityTemplate template = draft(OWNER, TemplateStatus.DRAFT);
        when(repository.findById(template.getId())).thenReturn(Optional.of(template));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        asMember(OWNER);
        service.submit(template.getId());
        assertThat(template.getStatus()).isEqualTo(TemplateStatus.PENDING);

        asAdmin(ADMIN);
        TemplateResponse approved = service.approve(template.getId());
        assertThat(approved.status()).isEqualTo(TemplateStatus.APPROVED);
        assertThat(template.getReviewedBy()).isEqualTo(ADMIN);
    }

    @Test
    void submit_byNonOwner_is404NotFound_notForbidden() {
        asMember("some-other-user");
        CommunityTemplate template = draft(OWNER, TemplateStatus.PENDING);
        when(repository.findById(template.getId())).thenReturn(Optional.of(template));

        assertThatThrownBy(() -> service.submit(template.getId()))
                .isInstanceOf(TemplateNotFoundException.class);
    }

    // --------------------------- public visibility ---------------------------

    @Test
    void galleryDetail_forANonApprovedTemplate_is404() {
        // The public detail loader only ever asks for APPROVED - a pending/draft id resolves to
        // empty.
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndStatus(id, TemplateStatus.APPROVED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.galleryDetail(id))
                .isInstanceOf(TemplateNotFoundException.class);
        verify(repository).findByIdAndStatus(id, TemplateStatus.APPROVED);
    }

    // --------------------------- admin enforcement ---------------------------

    @Test
    void pendingQueue_asNonAdmin_isDenied() {
        asMember(OWNER);
        assertThatThrownBy(service::pendingQueue).isInstanceOf(TemplateNotFoundException.class);
        verify(repository, never()).findByStatusOrderBySubmittedAtAsc(any());
    }

    @Test
    void reject_asNonAdmin_isDenied() {
        asMember(OWNER);
        CommunityTemplate template = draft(OWNER, TemplateStatus.PENDING);
        assertThatThrownBy(() -> service.reject(template.getId(), "spam"))
                .isInstanceOf(TemplateNotFoundException.class);
    }

    @Test
    void reject_asAdmin_recordsReasonAndReviewer() {
        asAdmin(ADMIN);
        CommunityTemplate template = draft(OWNER, TemplateStatus.PENDING);
        when(repository.findById(template.getId())).thenReturn(Optional.of(template));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TemplateResponse response =
                service.reject(template.getId(), "Contains a hard-coded phone number");

        assertThat(response.status()).isEqualTo(TemplateStatus.REJECTED);
        assertThat(response.rejectReason()).isEqualTo("Contains a hard-coded phone number");
        assertThat(response.reviewedBy()).isEqualTo(ADMIN);
    }

    @Test
    void sec010_tenantAdminWithAllPermissions_cannotModerateTheSharedGallery() {
        asTenantAdminWithAllPermissions("attacker-admin");
        CommunityTemplate own = draft("attacker-admin", TemplateStatus.PENDING);
        when(repository.findById(own.getId())).thenReturn(Optional.of(own));
        UUID id = own.getId();

        assertThatThrownBy(() -> service.approve(id)).isInstanceOf(TemplateNotFoundException.class);
        assertThatThrownBy(() -> service.verify(id)).isInstanceOf(TemplateNotFoundException.class);
        assertThatThrownBy(() -> service.unverify(id))
                .isInstanceOf(TemplateNotFoundException.class);
        assertThatThrownBy(() -> service.reject(id, "x"))
                .isInstanceOf(TemplateNotFoundException.class);
        assertThatThrownBy(() -> service.adminWithdraw(id, "x"))
                .isInstanceOf(TemplateNotFoundException.class);
        assertThatThrownBy(service::pendingQueue).isInstanceOf(TemplateNotFoundException.class);
        assertThatThrownBy(service::approvedForModeration)
                .isInstanceOf(TemplateNotFoundException.class);

        assertThat(own.getStatus()).isEqualTo(TemplateStatus.PENDING);
        assertThat(own.isVerified()).isFalse();
        verify(repository, never()).findByStatusOrderBySubmittedAtAsc(any());
        verify(repository, never()).findByStatusOrderByReviewedAtDesc(any(TemplateStatus.class));
        verify(auditLogService, never())
                .record(any(), eq("template.approve"), any(), any(), any(), any());
        verify(auditLogService, never())
                .record(any(), eq("template.verify"), any(), any(), any(), any());
    }

    @Test
    void sec010_platformAdmin_canApproveAndVerify() {
        asAdmin(ADMIN);
        CommunityTemplate pending = draft(OWNER, TemplateStatus.PENDING);
        when(repository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.approve(pending.getId()).status()).isEqualTo(TemplateStatus.APPROVED);
        assertThat(service.verify(pending.getId()).verified()).isTrue();
    }

    @Test
    void sec010_tenantListingOfSubmissionsSeesOnlyItsOwn() {
        asTenantAdminWithAllPermissions("tenant-a-admin");
        CommunityTemplate mine = draft("tenant-a-admin", TemplateStatus.PENDING);
        when(repository.findBySubmittedByOrderByUpdatedAtDesc("tenant-a-admin"))
                .thenReturn(List.of(mine));

        List<TemplateResponse> result = service.mine();

        assertThat(result).extracting(TemplateResponse::submittedBy).containsOnly("tenant-a-admin");
        verify(repository).findBySubmittedByOrderByUpdatedAtDesc("tenant-a-admin");
        verify(repository, never()).findByStatusOrderBySubmittedAtAsc(any());
    }

    @Test
    void sec010_moderationControllerIsAnnotatedPlatformAdminOnly() {
        org.springframework.security.access.prepost.PreAuthorize pre =
                AdminTemplateController.class.getAnnotation(
                        org.springframework.security.access.prepost.PreAuthorize.class);
        assertThat(pre).isNotNull();
        assertThat(pre.value()).isEqualTo("hasAuthority('ROLE_platform-admin')");
    }
}
