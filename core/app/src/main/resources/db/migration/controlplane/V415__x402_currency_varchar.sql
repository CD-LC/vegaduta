-- Type fix: V405 created control_plane.x402_offer.currency as CHAR(3); the
-- X402Offer entity maps currency as a length-3 String, which Hibernate schema
-- validation expects as varchar(3). CHAR reports Types#CHAR and the boot-time
-- check fails. V405 is already recorded in flyway_schema_history, so this
-- forward ALTER converges the column type.

ALTER TABLE control_plane.x402_offer
    ALTER COLUMN currency TYPE VARCHAR(3);
