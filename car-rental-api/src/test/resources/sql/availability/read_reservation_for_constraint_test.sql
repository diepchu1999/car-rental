-- Đọc riêng từng cận, không phân tích chuỗi biểu diễn range trong Java.
SELECT id, code, vehicle_id, kind, status, booking_code, reason,
       lower(period) AS starts_at, upper(period) AS ends_at,
       lower_inc(period) AS start_inclusive, upper_inc(period) AS end_inclusive,
       upper_inf(period) AS end_unbounded,
       hold_expires_at, created_at, status_changed_at
FROM availability.reservation
WHERE code = :code;
