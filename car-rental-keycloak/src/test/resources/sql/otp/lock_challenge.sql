-- Chỉ giữ hàng thử trong transaction của test để tái hiện cuộc đua tất định; không bỏ constraint.
SELECT id FROM car_rental_otp.challenge WHERE challenge_id = ? FOR UPDATE;
