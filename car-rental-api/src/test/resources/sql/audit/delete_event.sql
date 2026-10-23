-- Chỉ chạy trong transaction rollback của test.
DELETE FROM audit.audit_event WHERE request_id = :requestId;
