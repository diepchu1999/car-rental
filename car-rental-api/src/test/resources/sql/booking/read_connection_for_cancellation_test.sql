-- Chứng minh hai worker đang giữ hai connection PostgreSQL riêng trước khi tranh cùng dòng.
SELECT pg_backend_pid();
