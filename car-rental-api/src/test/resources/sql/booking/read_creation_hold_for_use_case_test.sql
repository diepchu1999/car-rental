-- Đối chiếu chính xác hạn, đệm và mã liên kết sau commit, không dựa vào mock.
SELECT code, booking_code, hold_expires_at, lower(period) AS starts_at, upper(period) AS ends_at
FROM availability.reservation
WHERE vehicle_id = :vehicleId;
