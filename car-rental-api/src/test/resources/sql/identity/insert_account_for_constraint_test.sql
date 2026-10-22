-- Ghi thẳng để kiểm ràng buộc V010, không đi qua validation domain.
INSERT INTO identity.account (code, keycloak_subject, phone_number, terms_version, consented_at, created_at)
VALUES (:code, :subject, :phone, :version, CAST(:consentedAt AS TIMESTAMPTZ), CAST(:createdAt AS TIMESTAMPTZ))
RETURNING id;
