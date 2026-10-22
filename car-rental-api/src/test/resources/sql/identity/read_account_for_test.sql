-- Kiểm CSDL giữ chính xác dữ liệu, không tự điền consent cho nhân viên.
SELECT code, keycloak_subject, phone_number, terms_version, consented_at, created_at
FROM identity.account WHERE id = :id;
