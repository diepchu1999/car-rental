-- Chỉ dùng trong test để nhận diện phiên kết nối PostgreSQL.
-- Phải gọi trong transaction cần theo dõi để lấy đúng PID của phiên đó.
-- Đây là PID phía PostgreSQL, không phải ID của luồng Java.
SELECT pg_catalog.pg_backend_pid() AS backend_pid;