-- Quan sát độc lập sau khi cả hai transaction đua đã kết thúc.
SELECT count(*) FROM booking.booking WHERE customer_account_code = :customer_account_code AND status = 'PENDING_PAYMENT';
