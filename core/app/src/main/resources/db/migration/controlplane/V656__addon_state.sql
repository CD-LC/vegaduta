-- V656: addon_state - what the install's owner asked for, per optional add-on, and what the host last reported.
-- Control plane, not a tenant schema: an add-on is a container or a model on the machine the install runs on
-- and is shared by everyone who uses it; the host-side installer that applies it has no tenant. Shared Flyway
-- namespace with tenant/.
--
-- One row per add-on that was ever touched (the catalog is addons/catalog.json in core/providers); no row means
-- "the catalog default, or whatever core can see running". Only rows for catalog ids are ever written; the id is
-- not a foreign key because the catalog is a file, not a table.
--   desired          the wish: true = switched on in the console
--   rev              counts changes of the wish (1 on the first change; 0 for a row the host's first report created)
--   desired_at/by    when and by whom (subject) the wish last changed; desired_by is NULL for a host-created row
--   reported_status  the host's last report: running | stopped | applying | failed
--   reported_rev     the rev the host acted on; a report for an older rev says nothing about the current wish
--   lock_version     optimistic lock, so two writers racing on one add-on cannot both win
CREATE TABLE IF NOT EXISTS control_plane.addon_state (
    addon_id         VARCHAR(64)  PRIMARY KEY,
    desired          BOOLEAN      NOT NULL,
    rev              BIGINT       NOT NULL DEFAULT 1,
    desired_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    desired_by       VARCHAR(255) NULL,
    reported_status  VARCHAR(16)  NULL,
    reported_rev     BIGINT       NULL,
    reported_message VARCHAR(500) NULL,
    reported_at      TIMESTAMPTZ  NULL,
    lock_version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT addon_state_reported_status_chk
        CHECK (reported_status IS NULL OR reported_status IN ('running', 'stopped', 'applying', 'failed')),
    CONSTRAINT addon_state_rev_chk CHECK (rev >= 0 AND (reported_rev IS NULL OR reported_rev >= 0))
);
