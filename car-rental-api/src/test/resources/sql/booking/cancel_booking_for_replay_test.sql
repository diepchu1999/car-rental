-- Chỉ dựng trạng thái sau tạo để kiểm replay; không thay cho use case huỷ ở phần kế tiếp.
UPDATE booking.booking
SET status = 'CANCELLED', status_changed_at = :changedAt
WHERE code = :code AND status = 'PENDING_PAYMENT';
