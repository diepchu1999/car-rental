-- Đọc độc lập sau transaction; FK chặn dòng con mồ côi.
SELECT (SELECT count(*) FROM booking.booking WHERE code = :code) AS headers,
       (SELECT count(*) FROM booking.price_line l JOIN booking.booking b ON b.id = l.booking_id WHERE b.code = :code) AS lines,
       (SELECT count(*) FROM booking.status_history h JOIN booking.booking b ON b.id = h.booking_id WHERE b.code = :code) AS history;
