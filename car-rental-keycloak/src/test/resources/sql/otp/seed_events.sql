-- Chỉ chạy trong PostgreSQL tạm do test dựng; tạo lịch sử mà không phải đợi một ngày.
INSERT INTO car_rental_otp.attempt_event(phone_key, ip_key, kind, occurred_at)
SELECT source.phone_key, source.ip_key, ?, clock_timestamp() - (? * INTERVAL '1 second')
FROM (
    SELECT event.phone_key, event.ip_key
    FROM car_rental_otp.attempt_event event
    JOIN car_rental_otp.challenge challenge ON challenge.phone_key = event.phone_key
    WHERE challenge.challenge_id = ? AND event.kind = 'SENT'
    ORDER BY event.id LIMIT 1
) source CROSS JOIN generate_series(1, CAST(? AS INTEGER));
