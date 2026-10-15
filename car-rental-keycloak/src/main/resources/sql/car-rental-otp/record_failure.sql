-- Lượt sai và sự kiện FAILED được commit cùng nhau, không rollback theo màn hình đăng nhập.
UPDATE car_rental_otp.challenge SET failed_attempts = failed_attempts + 1, state = ?
WHERE challenge_id = ? AND state = 'ACTIVE';
