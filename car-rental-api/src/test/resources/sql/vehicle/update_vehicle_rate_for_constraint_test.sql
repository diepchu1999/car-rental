-- Cố tình ghi thẳng để chứng minh CHECK BR-234, không qua domain.
UPDATE vehicle.vehicle
SET daily_rate = :dailyRate, status = :status
WHERE code = :code;
