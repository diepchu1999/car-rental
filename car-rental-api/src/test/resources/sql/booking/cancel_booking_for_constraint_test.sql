-- Chỉ dùng để kiểm partial unique giải phóng khách khi trạng thái không còn chờ.
UPDATE booking.booking SET status = 'CANCELLED', status_changed_at = :changed_at WHERE id = :id;
