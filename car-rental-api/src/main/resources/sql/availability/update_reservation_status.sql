-- Application kiểm cạnh chuyển bằng domain theo status-flow §2, BR-103.
-- CSDL kiểm trạng thái cũ ngay trong UPDATE để chống ghi đè dưới đồng thời.
-- Không sửa khoảng khóa hoặc hạn; COMPLETED vẫn giữ đệm (BR-109, BR-116).
UPDATE availability.reservation
SET status = :newStatus,
    status_changed_at = :changedAt
WHERE code = :code
  AND status = :expectedStatus;
