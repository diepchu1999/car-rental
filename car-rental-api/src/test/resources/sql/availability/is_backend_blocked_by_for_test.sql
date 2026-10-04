-- Quan sát tranh chấp thật qua kết nối thứ ba, không tự tạo khóa thay adapter.
SELECT :blockingPid = ANY (pg_catalog.pg_blocking_pids(:waitingPid)) AS is_blocked;
