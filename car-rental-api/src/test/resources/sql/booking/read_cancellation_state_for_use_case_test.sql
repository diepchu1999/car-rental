-- Đọc độc lập sau commit/rollback, không đi qua spy hoặc trả lại aggregate trong bộ nhớ.
SELECT b.status AS booking_status, r.status AS reservation_status,
       b.status_changed_at, r.status_changed_at AS reservation_changed_at,
       h.from_status, h.to_status, h.actor_account_code, h.reason, h.occurred_at,
       (SELECT count(*) FROM booking.status_history WHERE booking_id = b.id) AS history_count,
       (SELECT count(*) FROM booking.price_line WHERE booking_id = b.id) AS line_count
FROM booking.booking b
JOIN availability.reservation r ON r.code = b.reservation_code
LEFT JOIN booking.status_history h ON h.booking_id = b.id AND h.to_status = 'CANCELLED'
WHERE b.code = :code;
