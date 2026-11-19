-- Cố đổi người thực hiện trong lịch sử.
UPDATE booking.status_history SET actor_account_code = 'TK-OTHER1' WHERE booking_id = :id;
