package com.agenticai.core.agents.skills;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** One Level 3 bundled text file of a custom {@link Skill}. */
@Entity
@Table(name = "skill_file")
public class SkillFile {

    @Id private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "skill_id", nullable = false)
    private Skill skill;

    @Column(nullable = false, length = 255)
    private String path;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    protected SkillFile() {}

    SkillFile(Skill skill, String path, String content) {
        this.id = UUID.randomUUID();
        this.skill = skill;
        this.path = path;
        this.content = content;
        this.sizeBytes = content.getBytes(StandardCharsets.UTF_8).length;
    }

    public UUID getId() {
        return id;
    }

    public String getPath() {
        return path;
    }

    public String getContent() {
        return content;
    }

    public int getSizeBytes() {
        return sizeBytes;
    }
}
