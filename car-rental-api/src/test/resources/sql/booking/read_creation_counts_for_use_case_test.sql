-- Đọc bằng connection độc lập sau khi use case đã commit hoặc rollback.
SELECT
    (SELECT count(*) FROM booking.booking WHERE customer_account_code = :owner) AS headers,
    (SELECT count(*) FROM booking.price_line p JOIN booking.booking b ON b.id = p.booking_id
        WHERE b.customer_account_code = :owner) AS lines,
    (SELECT count(*) FROM booking.status_history h JOIN booking.booking b ON b.id = h.booking_id
        WHERE b.customer_account_code = :owner) AS history,
    (SELECT count(*) FROM booking.idempotency WHERE customer_account_code = :owner) AS keys,
    (SELECT count(*) FROM availability.reservation WHERE vehicle_id = :vehicleId) AS reservations,
    (SELECT count(*) FROM availability.reservation WHERE vehicle_id = :vehicleId AND status = 'HELD') AS held;
