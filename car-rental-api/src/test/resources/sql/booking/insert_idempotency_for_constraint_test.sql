-- Dữ liệu thử trực tiếp để chứng minh constraint; không đi qua validation Java.
INSERT INTO booking.idempotency (
    customer_account_code,
    idempotency_key,
    quote_code,
    booking_code,
    created_at,
    expires_at
) VALUES (
    CAST(:customer_account_code AS text),
    CAST(:idempotency_key AS text),
    CAST(:quote_code AS text),
    CAST(:booking_code AS text),
    CAST(:created_at AS timestamptz),
    CAST(:expires_at AS timestamptz)
);
