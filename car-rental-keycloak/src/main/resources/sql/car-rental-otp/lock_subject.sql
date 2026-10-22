-- Giữ đến cuối transaction độc lập của Keycloak; không dùng khoá trong JVM.
SELECT id FROM car_rental_otp.subject_lock WHERE subject_key = ? FOR UPDATE;
