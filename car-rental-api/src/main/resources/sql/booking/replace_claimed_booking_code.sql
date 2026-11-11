-- Chỉ dùng cho claim mới khi sinh lại mã DT bị trùng, không kéo dài thời hạn key.
UPDATE booking.idempotency SET booking_code = :replacementCode
WHERE customer_account_code = :customerAccountCode AND idempotency_key = :key
  AND quote_code = :quoteCode AND booking_code = :bookingCode
  AND created_at = :createdAt AND expires_at = :expiresAt;
