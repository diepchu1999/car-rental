-- Đọc lại tiền/hạn để chứng minh đổi trạng thái không đổi snapshot.
SELECT deposit, balance, hold_expires_at, status FROM booking.booking WHERE id = :id;
