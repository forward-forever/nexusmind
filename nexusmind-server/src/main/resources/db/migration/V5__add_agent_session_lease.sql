ALTER TABLE agent_session
    ADD COLUMN active_run_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER knowledge_base_id,
    ADD COLUMN run_acquired_at DATETIME(3) NULL AFTER active_run_id,
    ADD COLUMN run_lease_until DATETIME(3) NULL AFTER run_acquired_at,
    ADD KEY idx_agent_session_active_lease (active_run_id, run_lease_until);
