-- BR-234: đổi giá ở mọi trạng thái; không ghi đè dữ liệu khác của xe.
UPDATE vehicle.vehicle
SET daily_rate = :dailyRate
WHERE code = :code;
