-- Tra định danh bên cung cấp, không tự tìm hoặc gộp tài khoản bằng số điện thoại.
SELECT code, keycloak_subject, phone_number, terms_version, consented_at
FROM identity.account WHERE keycloak_subject = :subject;
