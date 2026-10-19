-- #971 - "as an eco system admin, I should be able to see the llm providers
-- and model for free tier, based on the region". Free-text (not an enum) on
-- purpose: this pool's providers (Groq, Together AI, Fireworks, Deepinfra,
-- ...) each host inference in whatever regions they operate, and there is no
-- existing tenant-level geo/region field in this codebase to route against
-- (Tenant has no country/region column - see Tenant.java) - so this is
-- deliberately scoped to admin-facing labeling/visibility only ("this row
-- serves US-East", "this row is the APAC failover"), not automatic
-- region-based routing. FreeTierProviderSelector's own priority-ordered
-- pick logic is unchanged; region is metadata layered on top, nullable so
-- every existing row keeps working with no admin action required.
ALTER TABLE control_plane.platform_hosted_llm_provider
    ADD COLUMN region VARCHAR(64);
