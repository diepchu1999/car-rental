-- ADR-0008: bảng reservation không có khóa ngoại xuyên module.
SELECT count(*)
FROM pg_catalog.pg_constraint
WHERE conrelid = 'availability.reservation'::regclass AND contype = 'f';
