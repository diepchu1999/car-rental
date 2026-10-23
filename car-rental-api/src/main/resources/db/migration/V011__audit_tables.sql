-- BR-130, ADR-0015: nhật ký chỉ thêm, không FK xuyên schema.
CREATE SCHEMA audit;

CREATE TABLE audit.audit_event (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_account_code TEXT NOT NULL,
    action TEXT NOT NULL,
    subject_account_code TEXT NOT NULL,
    resource_code TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    request_id TEXT NOT NULL,
    CONSTRAINT chk_audit_actor CHECK (actor_account_code COLLATE "C" ~ '^TK-[A-Z0-9]{6}$'),
    CONSTRAINT chk_audit_subject CHECK (subject_account_code COLLATE "C" ~ '^TK-[A-Z0-9]{6}$'),
    CONSTRAINT chk_audit_action CHECK (action = 'DOCUMENT_VIEW_OPENED'),
    CONSTRAINT chk_audit_resource CHECK (resource_code COLLATE "C" ~ '^GT-[A-Z0-9]{6}$'),
    CONSTRAINT chk_audit_time CHECK (isfinite(occurred_at)),
    CONSTRAINT chk_audit_request CHECK (request_id COLLATE "C" ~ '^[A-Za-z0-9-]{1,64}$')
);

CREATE INDEX idx_audit_resource_time ON audit.audit_event (resource_code, occurred_at, id);

-- Chặn cả lệnh tác động 0 dòng; chủ CSDL vẫn có thể tháo trigger, không phải chống DBA.
CREATE FUNCTION audit.reject_event_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '23514',
        MESSAGE = 'Audit events are append-only.',
        CONSTRAINT = 'chk_audit_event_append_only',
        SCHEMA = 'audit', TABLE = 'audit_event';
END;
$$;

CREATE TRIGGER trg_audit_event_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON audit.audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION audit.reject_event_mutation();
