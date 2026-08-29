-- Ghi trạng thái mới sau khi domain cho phép chuyển trạng thái.
-- Phục vụ BR-010 và máy trạng thái xe trong status-flow mục 4.
-- Kiểm trạng thái cũ và cập nhật trong cùng một câu lệnh.
-- Không thay đổi loại sở hữu, biển số, chi nhánh hoặc giấy tờ.
UPDATE vehicle.vehicle
SET status = :newStatus
WHERE code = :code
  AND status = :expectedStatus;