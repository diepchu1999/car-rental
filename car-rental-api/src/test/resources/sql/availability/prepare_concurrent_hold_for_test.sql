-- Chỉ dùng trong transaction của test để giới hạn thời gian chờ PostgreSQL.
-- PID xác nhận các luồng giữ các kết nối thật khác nhau trước khi cùng ghi.
SELECT set_config('lock_timeout', '20s', true),
       set_config('statement_timeout', '25s', true),
       pg_backend_pid() AS backend_pid;
