-- ADR-0008: không có FK chéo module.
SELECT count(*) FROM pg_constraint c JOIN pg_class parent ON parent.oid = c.conrelid JOIN pg_class referenced ON referenced.oid = c.confrelid WHERE c.contype = 'f' AND parent.relnamespace = 'booking'::regnamespace AND referenced.relnamespace <> parent.relnamespace;
