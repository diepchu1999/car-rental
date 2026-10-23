-- Chỉ chạy trong transaction rollback của test.
UPDATE audit.audit_event SET action = action WHERE request_id = :requestId;
