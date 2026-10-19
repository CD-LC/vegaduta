-- Type fix: V403/V404 created commerce_mandate.currency and
-- agent_wallet.currency as CHAR(3); the JPA entities map currency as a
-- length-3 String, which Hibernate schema validation expects as varchar(3).
-- CHAR pads and reports Types#CHAR, so the boot-time schema check fails with
-- "wrong column type ... found [bpchar], expecting [varchar(3)]".
-- Those files are already recorded in flyway_schema_history, so this
-- forward ALTER converges both fresh and already-migrated schemas.

ALTER TABLE commerce_mandate
    ALTER COLUMN currency TYPE VARCHAR(3);

ALTER TABLE agent_wallet
    ALTER COLUMN currency TYPE VARCHAR(3);
