ALTER TABLE agent_session
    ADD COLUMN session_type VARCHAR(16) NOT NULL DEFAULT 'NORMAL' AFTER knowledge_base_id,
    ADD KEY idx_agent_session_type_updated (session_type, updated_at),
    ADD CONSTRAINT chk_agent_session_type CHECK (session_type IN ('NORMAL', 'EVALUATION'));
