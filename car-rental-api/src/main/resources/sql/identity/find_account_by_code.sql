-- Chỉ truy cập schema identity; không join bảng người dùng Keycloak hay schema branch.
SELECT code, keycloak_subject, phone_number, terms_version, consented_at
FROM identity.account WHERE code = :code;
