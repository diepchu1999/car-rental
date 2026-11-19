-- Phép thử phá hoại nằm trong transaction rollback của test.
DELETE FROM booking.status_history WHERE booking_id = :id;
