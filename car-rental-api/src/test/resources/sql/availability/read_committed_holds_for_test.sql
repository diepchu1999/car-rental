-- Đọc sau khi mọi transaction đã kết thúc, không chỉ đếm kết quả Java.
SELECT id, code, vehicle_id, booking_code, kind, status,
       lower(period) AS starts_at, upper(period) AS ends_at,
       lower_inc(period) AS start_inclusive, upper_inc(period) AS end_inclusive
FROM availability.reservation
WHERE vehicle_id IN (:vehicleIds)
ORDER BY id;
