INSERT INTO audit.audit_event (action) VALUES ('UPDATE DELETE TRUNCATE DROP TABLE');
SELECT id FROM audit.audit_event FOR UPDATE;
SELECT id FROM audit.audit_event FOR NO KEY UPDATE;
