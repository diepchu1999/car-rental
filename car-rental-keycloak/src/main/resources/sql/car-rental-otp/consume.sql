-- BR-129: tiêu thụ một lần; câu lệnh này nằm trong transaction đã khoá số/IP.
UPDATE car_rental_otp.challenge SET state = 'CONSUMED', consumed_at = ?
WHERE challenge_id = ? AND state = 'ACTIVE';
