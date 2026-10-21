-- Thread list (GET /api/chat/threads): a subject's chat_branch_session rows,
-- newest activity first, paged by updated_at. Without this the list is a
-- sequential scan per page over every session of the tenant.
CREATE INDEX IF NOT EXISTS idx_chat_branch_session_owner
    ON chat_branch_session (owner_subject, updated_at DESC);
