-- Chỉ là fixture scanner, không được thực thi lên database.
UPDATE audit.audit_event SET action = 'CHANGED';
DELETE FROM audit.audit_event;
TRUNCATE audit.audit_event;
DROP TABLE audit.audit_event;

-- Bỏ qua khai báo trigger không được bỏ luôn DML ở statement kế tiếp.
CREATE TRIGGER fixture_guard BEFORE UPDATE OR DELETE OR TRUNCATE ON audit.audit_event
FOR EACH STATEMENT EXECUTE FUNCTION audit.reject_event_mutation();
UPDATE audit.audit_event SET action = 'CHANGED';
