-- Chỉ FK nội bộ identity được phép theo ADR-0008.
SELECT c.conname, target_namespace.nspname AS target_schema
FROM pg_constraint c
JOIN pg_class source_table ON source_table.oid = c.conrelid
JOIN pg_namespace source_namespace ON source_namespace.oid = source_table.relnamespace
JOIN pg_class target_table ON target_table.oid = c.confrelid
JOIN pg_namespace target_namespace ON target_namespace.oid = target_table.relnamespace
WHERE c.contype = 'f' AND source_namespace.nspname = 'identity';
