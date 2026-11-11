-- Đọc sau UPSERT đã chờ transaction thắng; không phải SELECT trước để quyết định có được INSERT.
SELECT customer_account_code, idempotency_key, quote_code, booking_code, created_at, expires_at
FROM booking.idempotency
WHERE customer_account_code = :customerAccountCode AND idempotency_key = :key
FOR UPDATE;
