-- Đọc độc lập sau commit/rollback, kiểm key không bị giữ lại do giao dịch thất bại.
SELECT count(*) FROM booking.idempotency WHERE customer_account_code = :customerAccountCode AND idempotency_key = :key;
