-- Chỉ dùng để kiểm ràng buộc CSDL khi UPDATE, không phải SQL use case.
UPDATE availability.reservation
SET status = :status, status_changed_at = :changedAt
WHERE code = :code;
