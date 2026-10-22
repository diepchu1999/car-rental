-- Dời thời gian sự kiện thử, không thay đồng hồ hay policy trong code main.
UPDATE car_rental_otp.attempt_event
SET occurred_at = clock_timestamp() - (? * INTERVAL '1 second')
WHERE kind = 'SENT' AND phone_key = (
    SELECT phone_key FROM car_rental_otp.challenge WHERE challenge_id = ?
);
