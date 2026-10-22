-- Quan sát độc lập thời điểm tạo, danh tính và bằng chứng đã lưu sau khi gọi adapter.
SELECT id, code, keycloak_subject, phone_number, terms_version, consented_at, created_at
FROM identity.account WHERE keycloak_subject = :subject;
