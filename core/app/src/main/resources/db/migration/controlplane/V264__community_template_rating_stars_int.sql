-- V251 created community_template_rating.stars as SMALLINT, but the
-- CommunityTemplateRating entity maps `int stars` -> INTEGER. Hibernate
-- schema validation fails boot on the mismatch (found int2, expecting
-- int4) - observed on the first prod boot of the V251 build, 2026-09-23.
-- Widening the column (not the entity) keeps the public Java API stable;
-- the 1..5 CHECK constraint survives the type change unchanged.
ALTER TABLE control_plane.community_template_rating
    ALTER COLUMN stars TYPE integer;
