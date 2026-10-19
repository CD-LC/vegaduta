-- Agent Response Quality Evaluation Framework (Master Roadmap 2026 item #16)
-- Stores evaluation scores and feedback for agent responses across multiple quality dimensions.
--
-- Each record captures:
-- - Individual scores for each evaluation type (helpfulness, accuracy, tone, etc.)
-- - Aggregated overall quality score
-- - Feedback explaining each score
-- - Token counts and response metadata for context
--
-- This is an append-only log: evaluations are never updated or deleted, enabling
-- historical analysis and trend tracking over time.

CREATE TABLE agent_quality_record (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id UUID NOT NULL,
    turn_number INTEGER NOT NULL,
    overall_score DECIMAL(3, 2) NOT NULL CHECK (overall_score >= 0 AND overall_score <= 1),
    user_message TEXT,
    agent_response TEXT,
    input_tokens BIGINT,
    output_tokens BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for querying quality history
CREATE INDEX idx_agent_quality_agent_id_created_at ON agent_quality_record(agent_id, created_at DESC);
CREATE INDEX idx_agent_quality_agent_id ON agent_quality_record(agent_id);
CREATE INDEX idx_agent_quality_created_at ON agent_quality_record(created_at DESC);

-- Map tables for per-evaluation-type scores and feedback
-- These use a PostgreSQL-native map via jsonb instead of separate table joins
-- for simpler queries and better performance at scale.
-- Migration: if using separate tables is preferred, these can be normalized.

CREATE TABLE agent_quality_record_scores (
    agent_quality_record_id UUID NOT NULL,
    score DECIMAL(3, 2) NOT NULL,
    scores_key VARCHAR(255) NOT NULL,
    PRIMARY KEY (agent_quality_record_id, scores_key),
    FOREIGN KEY (agent_quality_record_id) REFERENCES agent_quality_record(id) ON DELETE CASCADE
);

CREATE TABLE agent_quality_record_feedback (
    agent_quality_record_id UUID NOT NULL,
    feedback TEXT,
    feedback_key VARCHAR(255) NOT NULL,
    PRIMARY KEY (agent_quality_record_id, feedback_key),
    FOREIGN KEY (agent_quality_record_id) REFERENCES agent_quality_record(id) ON DELETE CASCADE
);

-- Index for bulk feedback/score retrieval
CREATE INDEX idx_quality_scores_record_id ON agent_quality_record_scores(agent_quality_record_id);
CREATE INDEX idx_quality_feedback_record_id ON agent_quality_record_feedback(agent_quality_record_id);
