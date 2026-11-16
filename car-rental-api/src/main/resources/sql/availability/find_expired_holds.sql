-- BR-103: danh sách chỉ là ứng viên; câu ghi phải kiểm lại hạn/trạng thái khi xử lý.
SELECT code, booking_code, hold_expires_at
FROM availability.reservation
WHERE status = 'HELD' AND hold_expires_at <= :expiredAt
ORDER BY hold_expires_at, code COLLATE "C";
