CREATE TABLE whatsapp_qr_connection (
    id UUID PRIMARY KEY,
    session_key VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    phone_number VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_whatsapp_qr_connection_session_key UNIQUE (session_key)
);
