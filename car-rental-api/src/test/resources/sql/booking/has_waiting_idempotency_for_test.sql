-- Quan sát connection của cùng user/DB test, không lấy query hoặc tham số ra báo cáo/log.
SELECT EXISTS (
    SELECT 1 FROM pg_stat_activity
    WHERE datname = current_database() AND pid <> pg_backend_pid()
      AND wait_event_type = 'Lock' AND query LIKE '%INSERT INTO booking.idempotency%'
);
