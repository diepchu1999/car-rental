-- Một khoá bền cho mỗi số/IP, dùng chung giữa mọi phiên và mọi nút Keycloak.
INSERT INTO car_rental_otp.subject_lock(subject_key) VALUES (?)
ON CONFLICT (subject_key) DO NOTHING;
