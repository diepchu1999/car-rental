-- Chỉ dùng trong test để kiểm tra tính bất biến theo BR-001.
-- Đổi sang loại sở hữu khác phải bị trigger từ chối.
-- Gán lại cùng loại sở hữu phải được phép.
UPDATE vehicle.vehicle
SET ownership_type = :ownershipType
WHERE code = :code;