-- Chỉ dùng trong test để quan sát việc chờ khóa thật trong PostgreSQL.
-- waitingPid là PID của phiên đang thử cập nhật.
-- blockingPid là PID của phiên đang giữ khóa cần quan sát.
-- Trả true khi phiên chặn nằm trong danh sách đang chặn phiên chờ.
SELECT
    :blockingPid = ANY (
        pg_catalog.pg_blocking_pids(:waitingPid)
        ) AS is_blocked;