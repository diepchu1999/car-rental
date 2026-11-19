-- api-guideline §5: key theo khách, thay đúng tại biên hết 24 giờ, không gia hạn lúc phát lại.
INSERT INTO booking.idempotency (customer_account_code, idempotency_key, quote_code, booking_code, created_at, expires_at)
VALUES (:customerAccountCode, :key, :quoteCode, :bookingCode, :createdAt, :expiresAt)
ON CONFLICT ON CONSTRAINT uq_booking_idempotency_customer_key DO UPDATE
SET quote_code = EXCLUDED.quote_code, booking_code = EXCLUDED.booking_code,
    created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at
WHERE booking.idempotency.expires_at <= EXCLUDED.created_at
RETURNING customer_account_code, idempotency_key, quote_code, booking_code, created_at, expires_at;
