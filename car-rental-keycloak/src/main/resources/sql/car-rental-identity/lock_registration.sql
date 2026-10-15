-- BR-120: tuần tự hoá việc ghi đồng ý điều khoản cho cùng realm/số trong transaction Keycloak.
-- Khoá HMAC được truyền vào, không dùng số điện thoại bản rõ; không khoá lịch hay tài nguyên thuê.
SELECT pg_advisory_xact_lock(?);
