-- Cấm cả câu nằm ở module khác.
UPDATE ONLY "audit"."audit_event" SET action = 'CHANGED';
WITH changed AS (UPDATE audit.audit_event SET action = 'CHANGED' RETURNING id) SELECT * FROM changed;
INSERT INTO audit.audit_event (id) VALUES (1) ON CONFLICT (id) DO UPDATE SET action = 'CHANGED';
MERGE INTO audit.audit_event AS target USING identity.account AS source ON target.id = source.id
WHEN MATCHED THEN UPDATE SET action = 'CHANGED';
