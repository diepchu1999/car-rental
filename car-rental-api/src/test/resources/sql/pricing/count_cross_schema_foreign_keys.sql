-- ADR-0008: chỉ FK nội bộ pricing, không khóa schema vehicle/config vào báo giá.
SELECT count(*) FROM pg_constraint c
JOIN pg_class source_table ON source_table.oid = c.conrelid
JOIN pg_namespace source_schema ON source_schema.oid = source_table.relnamespace
JOIN pg_class target_table ON target_table.oid = c.confrelid
JOIN pg_namespace target_schema ON target_schema.oid = target_table.relnamespace
WHERE c.contype = 'f' AND source_schema.nspname = 'pricing' AND target_schema.nspname <> 'pricing';
