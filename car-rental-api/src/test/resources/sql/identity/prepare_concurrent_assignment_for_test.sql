-- Giới hạn chờ và xác nhận mỗi worker đã giữ một kết nối PostgreSQL riêng trước khi mở chốt.
SELECT set_config('lock_timeout', '15s', true),
       set_config('statement_timeout', '20s', true),
       pg_backend_pid() AS backend_pid;
