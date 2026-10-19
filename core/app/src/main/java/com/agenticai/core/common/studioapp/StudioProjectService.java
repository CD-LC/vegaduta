package com.agenticai.core.common.studioapp;

import com.agenticai.core.common.studioapp.StudioCollabRules.Role;
import com.agenticai.core.common.studioapp.StudioProjectRules.VersionIn;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-side mirror of Phone-App-Studio projects and their version history
 * (see V370). The studio stays local-first: it keeps working from
 * localStorage and pushes here in the background; on a fresh device it pulls
 * from here. The server therefore never generates content, only stores it.
 *
 * <p>Rules: a project belongs to the member who created it (a non-member
 * looks like "not found", never "forbidden"); versions are immutable and
 * keyed by their client timestamp so re-syncing is idempotent; only the
 * newest {@link StudioProjectRules#MAX_VERSIONS} are kept.
 *
 * <p>Collaboration (V372): the owner may share a project with other tenant
 * members as VIEWER or EDITOR (see {@link StudioCollabRules}). An EDITOR may
 * add versions (recorded with their subject) but cannot rename, change
 * settings, delete or share; a VIEWER is read-only. A member who lacks a
 * permission gets {@code forbidden} (they already know the project exists).
 */
@Service
public class StudioProjectService {

    public record ProjectIn(String name, String emoji, String settings, Long createdAt, List<VersionIn> versions) {
    }

    public record Detail(StudioProject project, List<StudioProjectVersion> versions, Role role) {
    }

    public record Summary(StudioProject project, long versionCount, Role role) {
    }

    /** A project together with the caller's effective role on it. */
    record Access(StudioProject project, Role role, String subject) {
    }

    private final StudioProjectRepository projects;
    private final StudioProjectVersionRepository versions;
    private final StudioProjectMemberRepository members;

    public StudioProjectService(
        StudioProjectRepository projects, StudioProjectVersionRepository versions,
        StudioProjectMemberRepository members
    ) {
        this.projects = projects;
        this.versions = versions;
        this.members = members;
    }

    @Transactional
    public Detail upsert(String id, ProjectIn in) {
        String subject = requireSubject();
        if (!StudioProjectRules.validId(id)) {
            throw new StudioProjectException("bad_id");
        }
        String settings = in.settings();
        if (settings != null && settings.length() > StudioProjectRules.MAX_SETTINGS_CHARS) {
            throw new StudioProjectException("settings_too_large");
        }
        List<VersionIn> incoming;
        try {
            incoming = StudioProjectRules.sanitize(in.versions());
        } catch (IllegalArgumentException e) {
            throw new StudioProjectException(e.getMessage());
        }

        Optional<StudioProject> found = projects.findById(id);
        Role role = Role.OWNER;
        if (found.isPresent() && !subject.equals(found.get().getOwnerSubject())) {
            role = memberRole(id, subject).orElseThrow(() -> new StudioProjectException("project_not_found"));
            if (!role.canWriteVersions()) {
                throw new StudioProjectException("forbidden");
            }
        }
        StudioProject p = found.orElseGet(() -> {
            StudioProject fresh = new StudioProject();
            fresh.setId(id);
            fresh.setOwnerSubject(subject);
            if (in.createdAt() != null && in.createdAt() > 0) {
                fresh.setCreatedAt(Instant.ofEpochMilli(in.createdAt()));
            }
            return fresh;
        });
        if (role.canAdminister()) {
            // Only the owner changes name/emoji/settings (connections, publish, auth, ai).
            String name = StudioProjectRules.clip(in.name(), StudioProjectRules.MAX_NAME_CHARS);
            p.setName(name == null || name.isEmpty() ? "Untitled" : name);
            p.setEmoji(StudioProjectRules.clip(in.emoji(), 16));
            p.setSettings(settings);
        }
        p.setUpdatedAt(Instant.now());
        projects.save(p);

        Set<Long> have = new HashSet<>(versions.findAtMsByProjectId(id));
        for (VersionIn v : incoming) {
            if (have.contains(v.at())) {
                continue; // versions are immutable: same timestamp = same version
            }
            StudioProjectVersion row = new StudioProjectVersion();
            row.setProjectId(id);
            row.setAtMs(v.at());
            row.setNote(v.note());
            row.setSource(v.source());
            row.setHtml(v.html());
            row.setAuthorSubject(subject);
            versions.save(row);
            have.add(v.at());
        }
        List<Long> surplus = StudioProjectRules.surplus(List.copyOf(have));
        if (!surplus.isEmpty()) {
            versions.deleteByProjectIdAndAtMsIn(id, surplus);
        }
        return new Detail(p, versions.findByProjectIdOrderByAtMsAsc(id), role);
    }

    /** Owned projects plus those shared with the caller, newest first. */
    @Transactional(readOnly = true)
    public List<Summary> list() {
        String subject = requireSubject();
        List<Summary> out = new ArrayList<>();
        for (StudioProject p : projects.findByOwnerSubjectOrderByUpdatedAtDesc(subject)) {
            out.add(new Summary(p, versions.countByProjectId(p.getId()), Role.OWNER));
        }
        for (StudioProjectMember m : members.findByMemberSubject(subject)) {
            Optional<StudioProject> p = projects.findById(m.getProjectId());
            Optional<Role> role = StudioCollabRules.parseInviteRole(m.getRole());
            if (p.isPresent() && role.isPresent()) {
                out.add(new Summary(p.get(), versions.countByProjectId(p.get().getId()), role.get()));
            }
        }
        out.sort((a, b) -> b.project().getUpdatedAt().compareTo(a.project().getUpdatedAt()));
        return out;
    }

    @Transactional(readOnly = true)
    public Detail detail(String id) {
        Access a = access(id);
        return new Detail(a.project(), versions.findByProjectIdOrderByAtMsAsc(a.project().getId()), a.role());
    }

    @Transactional
    public void delete(String id) {
        Access a = access(id);
        if (!a.role().canAdminister()) {
            throw new StudioProjectException("forbidden");
        }
        projects.delete(a.project()); // versions, members, comments go with it (ON DELETE CASCADE)
    }

    /**
     * Resolves the caller's effective role on a project: owner, member, or
     * "project_not_found" for everyone else (never leaks existence).
     */
    Access access(String id) {
        String subject = requireSubject();
        if (!StudioProjectRules.validId(id)) {
            throw new StudioProjectException("bad_id");
        }
        StudioProject p = projects.findById(id)
            .orElseThrow(() -> new StudioProjectException("project_not_found"));
        if (subject.equals(p.getOwnerSubject())) {
            return new Access(p, Role.OWNER, subject);
        }
        Role role = memberRole(id, subject).orElseThrow(() -> new StudioProjectException("project_not_found"));
        return new Access(p, role, subject);
    }

    private Optional<Role> memberRole(String projectId, String subject) {
        return members.findByProjectIdAndMemberSubject(projectId, subject)
            .flatMap(m -> StudioCollabRules.parseInviteRole(m.getRole()));
    }

    static String requireSubject() {
        return TenantContext.current().map(TenantContext::getSubject)
            .filter(s -> !s.isBlank())
            .orElseThrow(() -> new StudioProjectException("no_subject"));
    }

    public static class StudioProjectException extends RuntimeException {
        public StudioProjectException(String code) {
            super(code);
        }
    }
}
