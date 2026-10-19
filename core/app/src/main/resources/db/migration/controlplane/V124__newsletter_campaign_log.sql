-- Platform-admin "Newsletter / news broadcast" feature - a simple send log
-- so a platform admin can see when a broadcast went out, its subject, and
-- how many tenant-admin recipients it actually reached. Written once per
-- POST /api/platform/newsletter call by NewsletterService#send (see that
-- class's javadoc). Read/written via plain JdbcTemplate, not a JPA entity -
-- no ORM mapping needed, this is a write-mostly audit log, not a domain
-- object this feature ever needs to query back through Hibernate.
--
-- Version note: this repo's Flyway locations merge controlplane/ and
-- tenant/ into one shared version sequence (see application.yml's flyway
-- comment) - V124 was chosen assuming V123 is reserved elsewhere for the
-- news_opt_in column this feature reads from control_plane.tenant (owned by
-- a separate, in-flight workstream); this migration does not add that
-- column itself.
CREATE TABLE control_plane.newsletter_campaign (
    id         UUID PRIMARY KEY,
    subject    VARCHAR(500) NOT NULL,
    sent_count INTEGER      NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    created_by VARCHAR(255)
);

CREATE INDEX idx_newsletter_campaign_created_at ON control_plane.newsletter_campaign (created_at DESC);
