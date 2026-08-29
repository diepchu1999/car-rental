DELETE FROM booking.temporary_quote
WHERE expires_at < CURRENT_TIMESTAMP;

TRUNCATE TABLE booking.import_staging;

DROP TABLE booking.obsolete_import_staging;
