-- Đọc độc lập sau commit: không được có hai bản ghi đang chặn chồng nhau trên cùng xe.
SELECT count(*)
FROM availability.reservation AS first
JOIN availability.reservation AS second
  ON first.vehicle_id = second.vehicle_id
 AND first.id < second.id
 AND first.period && second.period
WHERE first.vehicle_id = :vehicleId
  AND first.status IN ('HELD', 'CONFIRMED', 'IN_USE', 'BLOCKED', 'COMPLETED')
  AND second.status IN ('HELD', 'CONFIRMED', 'IN_USE', 'BLOCKED', 'COMPLETED');
