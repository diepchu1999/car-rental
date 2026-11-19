-- BR-103/304: CAS đơn trước giữ chỗ; chỉ bên thắng ghi lịch sử hệ thống.
WITH cancelled AS (
    UPDATE booking.booking
    SET status = 'CANCELLED', status_changed_at = :changedAt
    WHERE code = :code AND reservation_code = :reservationCode
      AND status = 'PENDING_PAYMENT'
      AND hold_expires_at = :holdExpiresAt AND hold_expires_at <= :changedAt
      AND status_changed_at <= :changedAt
    RETURNING id, status_changed_at
)
INSERT INTO booking.status_history (booking_id, from_status, to_status, actor_account_code, reason, occurred_at)
SELECT id, 'PENDING_PAYMENT', 'CANCELLED', NULL, 'HOLD_EXPIRED', status_changed_at
FROM cancelled;
