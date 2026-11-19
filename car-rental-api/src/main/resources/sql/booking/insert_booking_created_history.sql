-- status-flow §1: ghi sự kiện tạo đơn trong cùng transaction với header và dòng tiền.
INSERT INTO booking.status_history (booking_id, from_status, to_status, actor_account_code, reason, occurred_at)
VALUES (:bookingId, NULL, 'PENDING_PAYMENT', :actorAccountCode, 'CREATED', :occurredAt);
