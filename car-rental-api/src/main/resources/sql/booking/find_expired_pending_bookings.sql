-- BR-103: bắt cả đơn local mà giữ chỗ đã bị job cũ nhả; không JOIN schema availability.
SELECT code, reservation_code
FROM booking.booking
WHERE status = 'PENDING_PAYMENT' AND hold_expires_at <= :expiredAt
ORDER BY hold_expires_at, code COLLATE "C";
