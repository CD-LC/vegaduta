-- Dynamic Pricing Architecture - Phase 1: Database Schema
-- 
-- This migration adds pricing columns to the provider_model_catalog table
-- and creates supporting tables for pricing history and admin overrides.
-- 
-- Problem: The current pricing system uses a static catalog (LlmPricingCatalog.java)
-- with a punitive fallback rate of $3/$15 per MTok for unknown models, causing
-- budget exhaustion for users of new models even with minimal token usage.
-- 
-- Solution: Add pricing fields to the model catalog, enable dynamic pricing discovery
-- from provider APIs, and provide admin override capability.
--
-- Reference: Dynamic Pricing Architecture Design (2026-12-20)

-- Add pricing columns to existing provider_model_catalog table
ALTER TABLE control_plane.provider_model_catalog
ADD COLUMN input_price_per_million DECIMAL(10, 4),
ADD COLUMN output_price_per_million DECIMAL(10, 4),
ADD COLUMN cache_write_price_per_million DECIMAL(10, 4),
ADD COLUMN cache_read_price_per_million DECIMAL(10, 4),
ADD COLUMN pricing_source VARCHAR(32) NOT NULL DEFAULT 'static_fallback',
ADD COLUMN pricing_confidence VARCHAR(16) NOT NULL DEFAULT 'unknown',
ADD COLUMN pricing_fetched_at TIMESTAMPTZ,
ADD COLUMN pricing_metadata JSONB;

-- Add check constraints for pricing source and confidence
ALTER TABLE control_plane.provider_model_catalog
ADD CONSTRAINT chk_pricing_source 
    CHECK (pricing_source IN ('provider_api', 'provider_catalog', 'static_fallback', 'admin_override', 'conservative'));

ALTER TABLE control_plane.provider_model_catalog
ADD CONSTRAINT chk_pricing_confidence 
    CHECK (pricing_confidence IN ('high', 'medium', 'low', 'unknown'));

-- Add index for pricing queries
CREATE INDEX idx_provider_model_catalog_pricing ON control_plane.provider_model_catalog(pricing_source, pricing_confidence);

-- Create pricing history table for audit and rollback
CREATE TABLE control_plane.model_pricing_history (
    id              BIGSERIAL PRIMARY KEY,
    provider_name   VARCHAR(64)  NOT NULL,
    model_id        VARCHAR(255) NOT NULL,
    input_price_per_million DECIMAL(10, 4),
    output_price_per_million DECIMAL(10, 4),
    cache_write_price_per_million DECIMAL(10, 4),
    cache_read_price_per_million DECIMAL(10, 4),
    pricing_source VARCHAR(32) NOT NULL,
    pricing_confidence VARCHAR(16) NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_until TIMESTAMPTZ,
    change_reason TEXT,
    changed_by VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Add constraints to pricing history
ALTER TABLE control_plane.model_pricing_history
ADD CONSTRAINT chk_pricing_history_source 
    CHECK (pricing_source IN ('provider_api', 'provider_catalog', 'static_fallback', 'admin_override', 'conservative'));

ALTER TABLE control_plane.model_pricing_history
ADD CONSTRAINT chk_pricing_history_confidence 
    CHECK (pricing_confidence IN ('high', 'medium', 'low', 'unknown'));

-- Create indexes for pricing history queries
CREATE INDEX idx_pricing_history_model ON control_plane.model_pricing_history(provider_name, model_id, effective_from DESC);
CREATE INDEX idx_pricing_history_effective ON control_plane.model_pricing_history(effective_from, effective_until);
CREATE INDEX idx_pricing_history_changed_by ON control_plane.model_pricing_history(changed_by);

-- Create pricing overrides table for admin manual corrections
CREATE TABLE control_plane.model_pricing_overrides (
    id              BIGSERIAL PRIMARY KEY,
    provider_name   VARCHAR(64)  NOT NULL,
    model_id        VARCHAR(255) NOT NULL,
    input_price_per_million DECIMAL(10, 4) NOT NULL,
    output_price_per_million DECIMAL(10, 4) NOT NULL,
    cache_write_price_per_million DECIMAL(10, 4),
    cache_read_price_per_million DECIMAL(10, 4),
    override_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by VARCHAR(128),
    expires_at TIMESTAMPTZ,
    CONSTRAINT uq_pricing_overrides UNIQUE (provider_name, model_id)
);

-- Add check constraints to overrides table
ALTER TABLE control_plane.model_pricing_overrides
ADD CONSTRAINT chk_override_active 
    CHECK (expires_at IS NULL OR expires_at > created_at);

-- Create index for active overrides.
--
-- NOT a partial index. The original predicate was
--     WHERE expires_at IS NULL OR expires_at > NOW()
-- which Postgres rejects outright: "functions in index predicate must be
-- marked IMMUTABLE". NOW() is STABLE, not IMMUTABLE - an index whose
-- membership changed with wall-clock time could never stay consistent. The
-- expiry filter belongs in the query, not the index.
CREATE INDEX idx_pricing_overrides_active ON control_plane.model_pricing_overrides(
    provider_name,
    model_id,
    created_at
);

-- Add comment for documentation
COMMENT ON TABLE control_plane.model_pricing_history IS 'Audit trail of pricing changes for model cost tracking and rollback capability';
COMMENT ON TABLE control_plane.model_pricing_overrides IS 'Admin manual pricing corrections with expiration support for temporary adjustments';
COMMENT ON COLUMN control_plane.provider_model_catalog.pricing_source IS 'Source of pricing data: provider_api, provider_catalog, static_fallback, admin_override, conservative';
COMMENT ON COLUMN control_plane.provider_model_catalog.pricing_confidence IS 'Confidence level: high (direct from API), medium (from catalog), low (static/conservative), unknown';
