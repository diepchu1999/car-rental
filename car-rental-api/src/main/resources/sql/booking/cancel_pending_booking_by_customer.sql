-- BR-301/304: chỉ bên thắng CAS ghi lịch sử; use case nhả chỗ trong cùng transaction ngoài.
-- Không sửa snapshot hoặc hạn; tác nhân lấy từ chính đơn đã kiểm chủ (status-flow §1).
WITH cancelled AS (
    UPDATE booking.booking
    SET status = 'CANCELLED', status_changed_at = :changedAt
    WHERE code = :code
      AND customer_account_code = :customerAccountCode
      AND status = 'PENDING_PAYMENT'
      AND status_changed_at <= :changedAt
    RETURNING id, customer_account_code, status_changed_at
)
INSERT INTO booking.status_history (booking_id, from_status, to_status, actor_account_code, reason, occurred_at)
SELECT id, 'PENDING_PAYMENT', 'CANCELLED', customer_account_code, 'CUSTOMER_CANCELLED', status_changed_at
FROM cancelled;
