-- Kiểm hai index được yêu cầu ngoài các index tự sinh bởi constraint.
SELECT indexname, indexdef
FROM pg_catalog.pg_indexes
WHERE schemaname = 'availability' AND tablename = 'reservation';
