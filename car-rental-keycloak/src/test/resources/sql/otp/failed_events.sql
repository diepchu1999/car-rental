-- Đếm sự kiện sai đã commit của số thử.
SELECT count(*) FROM car_rental_otp.attempt_event
WHERE kind = 'FAILED' AND phone_key = (
    SELECT phone_key FROM car_rental_otp.challenge WHERE challenge_id = ?
);
