-- Master Roadmap 2026 item #18 fast-follow (#1077) - the "routing" half of
-- cost optimization that V95 (agent_response_caching, the "semantic caching"
-- half) deliberately left open. See Agent.java's costRoutingEnabled/
-- economyProviderName/economyModel javadoc for the full RouteLLM/NotDiamond-
-- style design: route a turn to a cheaper "economy" model instead of the
-- agent's normal model when QueryComplexityClassifier judges it simple.
--
-- V99, not V96/V97/V98 - core/app/src/main/resources/application.yml's
-- flyway.locations merges controlplane/ and tenant/ into one version
-- stream; V96 (controlplane/V96__tenant_data_residency_and_erasure.sql), V97
-- (tenant/V97__agent_e2b_enabled.sql), and V98 (tenant/V98__voice_dtmf_mapping.sql)
-- are all already taken as of this write.
ALTER TABLE agent
    ADD COLUMN cost_routing_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN economy_provider_name VARCHAR(32),
    ADD COLUMN economy_model VARCHAR(128);
