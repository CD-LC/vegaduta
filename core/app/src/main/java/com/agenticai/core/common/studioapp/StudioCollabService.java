package com.agenticai.core.common.studioapp;

import com.agenticai.core.common.studioapp.StudioCollabRules.Role;
import com.agenticai.core.common.studioapp.StudioMemberDirectory.Person;
import com.agenticai.core.common.studioapp.StudioProjectService.Access;
import com.agenticai.core.common.studioapp.StudioProjectService.StudioProjectException;
import com.agenticai.core.sharing.ShareRateLimiter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Members and comments for a studio project (V372). All authorization goes
 * through {@link StudioProjectService#access} and {@link StudioCollabRules}.
 */
@Service
public class StudioCollabService {

    public record MemberRow(StudioProjectMember member, Person person) {
    }

    private final StudioProjectService projects;
    private final StudioProjectMemberRepository members;
    private final StudioProjectCommentRepository comments;
    private final StudioProjectVersionRepository versions;
    private final StudioMemberDirectory directory;
    private final ShareRateLimiter rateLimiter;

    public StudioCollabService(
        StudioProjectService projects, StudioProjectMemberRepository members,
        StudioProjectCommentRepository comments, StudioProjectVersionRepository versions,
        StudioMemberDirectory directory, ShareRateLimiter rateLimiter
    ) {
        this.projects = projects;
        this.members = members;
        this.comments = comments;
        this.versions = versions;
        this.directory = directory;
        this.rateLimiter = rateLimiter;
    }

    // ---- members ----

    @Transactional(readOnly = true)
    public List<MemberRow> listMembers(String projectId) {
        Access a = projects.access(projectId); // any member may see who else is on the project
        List<MemberRow> out = new java.util.ArrayList<>();
        out.add(new MemberRow(null, directory.describe(a.project().getOwnerSubject())));
        for (StudioProjectMember m : members.findByProjectIdOrderByCreatedAtAsc(projectId)) {
            out.add(new MemberRow(m, directory.describe(m.getMemberSubject())));
        }
        return out;
    }

    /** Owner-only. Adds a tenant member by email, or changes their role if already a member. */
    @Transactional
    public MemberRow invite(String projectId, String email, String roleRaw) {
        Access a = projects.access(projectId);
        if (!a.role().canManageMembers()) {
            throw new StudioProjectException("forbidden");
        }
        Role role = StudioCollabRules.parseInviteRole(roleRaw)
            .orElseThrow(() -> new StudioProjectException("bad_role"));
        String clean = StudioCollabRules.cleanEmail(email)
            .orElseThrow(() -> new StudioProjectException("bad_email"));
        if (!directory.available()) {
            throw new StudioProjectException("directory_unavailable");
        }
        rateLimiter.assertGrantWriteAllowed(); // the email lookup is an enumeration oracle
        // Generic error for unknown / other-tenant so membership cannot be probed.
        Person person = directory.findInCallerTenant(clean)
            .orElseThrow(() -> new StudioProjectException("not_a_tenant_member"));
        if (person.subject().equals(a.project().getOwnerSubject())) {
            throw new StudioProjectException("already_owner");
        }
        Optional<StudioProjectMember> existing = members.findByProjectIdAndMemberSubject(projectId, person.subject());
        if (existing.isEmpty() && members.countByProjectId(projectId) >= StudioCollabRules.MAX_MEMBERS_PER_PROJECT) {
            throw new StudioProjectException("too_many_members");
        }
        StudioProjectMember m = existing.orElseGet(() -> {
            StudioProjectMember fresh = new StudioProjectMember();
            fresh.setProjectId(projectId);
            fresh.setMemberSubject(person.subject());
            fresh.setInvitedBy(a.subject());
            return fresh;
        });
        m.setRole(role.name());
        return new MemberRow(members.save(m), person);
    }

    /** Owner-only removal (idempotent). The owner cannot remove themselves - delete the project instead. */
    @Transactional
    public void removeMember(String projectId, String memberSubject) {
        Access a = projects.access(projectId);
        if (!a.role().canManageMembers()) {
            throw new StudioProjectException("forbidden");
        }
        members.findByProjectIdAndMemberSubject(projectId, memberSubject).ifPresent(members::delete);
    }

    // ---- comments ----

    @Transactional(readOnly = true)
    public List<StudioProjectComment> listComments(String projectId) {
        Access a = projects.access(projectId);
        return comments.findByProjectIdOrderByCreatedAtAsc(a.project().getId());
    }

    @Transactional
    public StudioProjectComment addComment(String projectId, String body, Long versionAtMs) {
        Access a = projects.access(projectId);
        if (!a.role().canComment()) {
            throw new StudioProjectException("forbidden");
        }
        String clean;
        try {
            clean = StudioCollabRules.cleanBody(body).orElseThrow(() -> new StudioProjectException("empty_comment"));
        } catch (IllegalArgumentException e) {
            throw new StudioProjectException(e.getMessage());
        }
        if (comments.countByProjectId(projectId) >= StudioCollabRules.MAX_COMMENTS_PER_PROJECT) {
            throw new StudioProjectException("too_many_comments");
        }
        if (versionAtMs != null && !versions.findAtMsByProjectId(projectId).contains(versionAtMs)) {
            versionAtMs = null; // anchor to a version that no longer exists (or never did): keep the comment, drop the anchor
        }
        StudioProjectComment c = new StudioProjectComment();
        c.setProjectId(projectId);
        c.setVersionAtMs(versionAtMs);
        c.setAuthorSubject(a.subject());
        c.setAuthorName(directory.describe(a.subject()).name());
        c.setBody(clean);
        return comments.save(c);
    }

    @Transactional
    public StudioProjectComment setResolved(String projectId, UUID commentId, boolean resolved) {
        Access a = projects.access(projectId);
        if (!StudioCollabRules.canResolveComment(a.role())) {
            throw new StudioProjectException("forbidden");
        }
        StudioProjectComment c = comments.findByIdAndProjectId(commentId, projectId)
            .orElseThrow(() -> new StudioProjectException("comment_not_found"));
        c.setResolved(resolved);
        return comments.save(c);
    }

    @Transactional
    public void deleteComment(String projectId, UUID commentId) {
        Access a = projects.access(projectId);
        Optional<StudioProjectComment> c = comments.findByIdAndProjectId(commentId, projectId);
        if (c.isEmpty()) {
            return; // idempotent
        }
        if (!StudioCollabRules.canDeleteComment(a.role(), c.get().getAuthorSubject(), a.subject())) {
            throw new StudioProjectException("forbidden");
        }
        comments.delete(c.get());
    }
}
