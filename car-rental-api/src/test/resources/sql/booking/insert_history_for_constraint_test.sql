-- Dữ liệu thử trực tiếp để chứng minh constraint; không đi qua validation Java.
INSERT INTO booking.status_history (
    booking_id,
    from_status,
    to_status,
    actor_account_code,
    reason,
    occurred_at
) VALUES (
    CAST(:booking_id AS bigint),
    CAST(:from_status AS text),
    CAST(:to_status AS text),
    CAST(:actor_account_code AS text),
    CAST(:reason AS text),
    CAST(:occurred_at AS timestamptz)
);
