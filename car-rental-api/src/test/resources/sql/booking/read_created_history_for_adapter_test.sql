-- Kiểm lịch sử ban đầu có đúng chủ và lý do, không chỉ đếm có bản ghi.
SELECT h.from_status, h.to_status, h.actor_account_code, h.reason, h.occurred_at
FROM booking.status_history h JOIN booking.booking b ON b.id = h.booking_id
WHERE b.code = :code ORDER BY h.id;
