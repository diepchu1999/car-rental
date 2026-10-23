-- Khai báo trigger chặn phá dữ liệu phải hợp lệ.
CREATE TRIGGER test_guard BEFORE UPDATE OR DELETE OR TRUNCATE ON audit.audit_event
FOR EACH STATEMENT EXECUTE FUNCTION audit.reject_event_mutation();
CREATE OR REPLACE TRIGGER test_guard BEFORE UPDATE ON audit.audit_event
FOR EACH STATEMENT EXECUTE FUNCTION audit.reject_event_mutation();
