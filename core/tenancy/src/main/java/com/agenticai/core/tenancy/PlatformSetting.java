package com.agenticai.core.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Generic, admin-editable key-value operational setting - see migration
 * {@code V91__platform_operational_settings.sql}'s header comment for why
 * this is a generic table rather than one more single-purpose singleton row
 * (the {@link PlatformKillSwitch}/{@code PlatformAlertSettings} pattern):
 * direct feedback specifically asked for a central "set all these values"
 * control, not a bespoke column for every future knob.
 *
 * <p>{@code value} is always a plain string - {@link PlatformSettingsService}
 * owns parsing it to whatever type a given key actually needs (int, so far),
 * the same way a {@code @Value("${...}")} property string already gets
 * parsed by Spring at injection time. Keeping the column untyped means
 * adding a new setting never requires a schema change, only a new row.
 */
@Entity
@Table(name = "platform_setting", schema = "control_plane")
public class PlatformSetting {

    @Id
    @Column(name = "key", length = 120)
    private String key;

    // TEXT, not a bounded varchar: several settings hold whole JSON documents
    // (the edge model manifest, the plugin catalog, abuse thresholds) and the
    // original VARCHAR(500) silently capped them - a plugin publish failed
    // with a bare 500 from "value too long for type character varying(500)".
    // Widened by controlplane/V234; must stay in step with that DDL because
    // core runs hibernate ddl-auto: validate.
    @Column(name = "value", nullable = false, columnDefinition = "TEXT")
    private String value;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected PlatformSetting() {
        // JPA
    }

    public PlatformSetting(String key, String value, String description) {
        this.key = key;
        this.value = value;
        this.description = description;
    }

    public void setValue(String value, String updatedBy) {
        this.value = value;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public String getDescription() {
        return description;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
