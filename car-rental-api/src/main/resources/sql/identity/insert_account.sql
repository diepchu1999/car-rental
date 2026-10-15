-- BR-120: UNIQUE bảo vệ cả subject, số và mã, kể cả hai request đầu tiên đồng thời.
-- Bảng account không có EXCLUDE. Khi không chèn, adapter đọc lại bằng statement mới
-- để phân loại xung đột; không coi mọi xung đột là trùng mã, không sửa tài khoản hiện hữu.
INSERT INTO identity.account (code, keycloak_subject, phone_number, terms_version, consented_at, created_at)
VALUES (:code, :subject, :phone, :version, :consentedAt, :createdAt)
ON CONFLICT DO NOTHING;
