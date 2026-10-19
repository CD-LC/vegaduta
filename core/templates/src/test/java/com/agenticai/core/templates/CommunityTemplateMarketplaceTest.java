package com.agenticai.core.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.templates.dto.TemplateRatingRequest;
import com.agenticai.core.templates.dto.TemplateRatingView;
import com.agenticai.core.templates.dto.TemplateResponse;
import com.agenticai.core.templates.dto.TemplateSummary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link CommunityTemplateService} marketplace features: ratings and the verified badge. */
class CommunityTemplateMarketplaceTest extends CommunityTemplateServiceTestBase {

    // --------------------------- marketplace: ratings ------------------------

    @Test
    void rate_anApprovedTemplateByAnotherMember_writesARatingAndRefreshesAggregates() {
        asMember("rater-1");
        CommunityTemplate template = draft(OWNER, TemplateStatus.APPROVED);
        when(repository.findByIdAndStatus(template.getId(), TemplateStatus.APPROVED))
                .thenReturn(Optional.of(template));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ratingRepository.findByTemplateIdAndRaterSubject(template.getId(), "rater-1"))
                .thenReturn(Optional.empty());
        when(ratingRepository.countByTemplateId(template.getId())).thenReturn(1L);
        when(ratingRepository.averageStars(template.getId())).thenReturn(4.0);

        TemplateSummary result =
                service.rate(template.getId(), new TemplateRatingRequest(4, "solid"));

        verify(ratingRepository).save(any(CommunityTemplateRating.class));
        assertThat(template.getRatingCount()).isEqualTo(1L);
        assertThat(template.getRatingAvg()).isEqualTo(4.0);
        assertThat(result.ratingCount()).isEqualTo(1L);
        verify(auditLogService)
                .record(eq("rater-1"), eq("template.rate"), eq("template"), any(), any(), any());
    }

    @Test
    void rate_twiceByTheSameMember_updatesTheExistingRow_neverASecondVote() {
        asMember("rater-1");
        CommunityTemplate template = draft(OWNER, TemplateStatus.APPROVED);
        CommunityTemplateRating existing =
                new CommunityTemplateRating(template.getId(), "rater-1", TENANT, 2, "meh");
        when(repository.findByIdAndStatus(template.getId(), TemplateStatus.APPROVED))
                .thenReturn(Optional.of(template));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ratingRepository.findByTemplateIdAndRaterSubject(template.getId(), "rater-1"))
                .thenReturn(Optional.of(existing));
        when(ratingRepository.countByTemplateId(template.getId())).thenReturn(1L);
        when(ratingRepository.averageStars(template.getId())).thenReturn(5.0);

        service.rate(template.getId(), new TemplateRatingRequest(5, "actually great"));

        assertThat(existing.getStars()).isEqualTo(5);
        verify(ratingRepository).save(existing); // the SAME row updated, not a new insert
        verify(auditLogService)
                .record(eq("rater-1"), eq("template.rerate"), eq("template"), any(), any(), any());
    }

    @Test
    void rate_ownTemplate_is409NotASilentVote() {
        asMember(OWNER);
        CommunityTemplate template = draft(OWNER, TemplateStatus.APPROVED);
        when(repository.findByIdAndStatus(template.getId(), TemplateStatus.APPROVED))
                .thenReturn(Optional.of(template));

        assertThatThrownBy(() -> service.rate(template.getId(), new TemplateRatingRequest(5, null)))
                .isInstanceOf(TemplateRatingForbiddenException.class);
        verify(ratingRepository, never()).save(any());
    }

    @Test
    void rate_aNonApprovedTemplate_is404() {
        asMember("rater-1");
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndStatus(id, TemplateStatus.APPROVED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rate(id, new TemplateRatingRequest(3, null)))
                .isInstanceOf(TemplateNotFoundException.class);
        verify(ratingRepository, never()).save(any());
    }

    @Test
    void unrate_removesTheRowAndRecomputesAggregates() {
        asMember("rater-1");
        CommunityTemplate template = draft(OWNER, TemplateStatus.APPROVED);
        when(repository.findByIdAndStatus(template.getId(), TemplateStatus.APPROVED))
                .thenReturn(Optional.of(template));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ratingRepository.countByTemplateId(template.getId())).thenReturn(0L);
        when(ratingRepository.averageStars(template.getId())).thenReturn(null);

        service.unrate(template.getId());

        verify(ratingRepository).deleteByTemplateIdAndRaterSubject(template.getId(), "rater-1");
        assertThat(template.getRatingCount()).isZero();
        assertThat(template.getRatingAvg()).isZero();
    }

    // --------------------------- marketplace: verified -----------------------

    @Test
    void verify_anApprovedTemplate_setsBadgeAndAudits() {
        asAdmin(ADMIN);
        CommunityTemplate template = draft(OWNER, TemplateStatus.APPROVED);
        when(repository.findById(template.getId())).thenReturn(Optional.of(template));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TemplateResponse response = service.verify(template.getId());

        assertThat(response.verified()).isTrue();
        assertThat(response.verifiedBy()).isEqualTo(ADMIN);
        verify(auditLogService)
                .record(eq(ADMIN), eq("template.verify"), eq("template"), any(), any(), any());
    }

    @Test
    void verify_aNonApprovedTemplate_isRejected() {
        asAdmin(ADMIN);
        CommunityTemplate template = draft(OWNER, TemplateStatus.PENDING);
        when(repository.findById(template.getId())).thenReturn(Optional.of(template));

        assertThatThrownBy(() -> service.verify(template.getId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(template.isVerified()).isFalse();
    }

    @Test
    void verify_asNonAdmin_isDenied() {
        asMember(OWNER);
        assertThatThrownBy(() -> service.verify(UUID.randomUUID()))
                .isInstanceOf(TemplateNotFoundException.class);
    }

    @Test
    void unverify_clearsTheBadge() {
        asAdmin(ADMIN);
        CommunityTemplate template = draft(OWNER, TemplateStatus.APPROVED);
        template.markVerified(ADMIN);
        when(repository.findById(template.getId())).thenReturn(Optional.of(template));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TemplateResponse response = service.unverify(template.getId());

        assertThat(response.verified()).isFalse();
        assertThat(template.getVerifiedBy()).isNull();
        verify(auditLogService)
                .record(eq(ADMIN), eq("template.unverify"), eq("template"), any(), any(), any());
    }

    @Test
    void galleryRatings_onlyForApprovedTemplates_andCarriesNoRaterIdentity() {
        CommunityTemplate template = draft(OWNER, TemplateStatus.APPROVED);
        when(repository.findByIdAndStatus(template.getId(), TemplateStatus.APPROVED))
                .thenReturn(Optional.of(template));
        CommunityTemplateRating rating =
                new CommunityTemplateRating(template.getId(), "rater-1", TENANT, 4, "good");
        when(ratingRepository.findByTemplateIdOrderByCreatedAtDesc(eq(template.getId()), any()))
                .thenReturn(List.of(rating));

        List<TemplateRatingView> views = service.galleryRatings(template.getId(), 0, 20);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).stars()).isEqualTo(4);
        // The public view must not leak the rater - the record carries no identity fields at all.
        assertThat(views.get(0).review()).isEqualTo("good");
    }
}
