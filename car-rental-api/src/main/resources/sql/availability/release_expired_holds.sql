-- BR-103: dùng hạn đóng băng lúc tạo, không tính lại từ cấu hình hiện tại.
-- Trạng thái và hạn kiểm cùng lúc ghi; không nhả khóa đã được xác nhận.
-- Đúng thời điểm hết hạn cũng nhả, nhất quán với Reservation.confirm.
-- Giữ hồ sơ và khoảng khóa, chỉ đổi trạng thái cùng mốc thay đổi gần nhất.
UPDATE availability.reservation
SET status = 'RELEASED',
    status_changed_at = :expiredAt
WHERE status = 'HELD'
  AND hold_expires_at <= :expiredAt;
