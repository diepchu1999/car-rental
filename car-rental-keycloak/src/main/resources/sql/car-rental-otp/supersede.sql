-- Mã gửi lại thay thế mã trước của cùng số, kể cả khác tab/flow.
UPDATE car_rental_otp.challenge SET state = 'SUPERSEDED'
WHERE phone_key = ? AND state = 'ACTIVE';
