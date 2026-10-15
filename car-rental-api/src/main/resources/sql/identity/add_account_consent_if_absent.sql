-- BR-120: một UPDATE có điều kiện, không ghi đè bằng chứng khi hai lượt bổ sung đồng thời.
UPDATE identity.account
SET terms_version = :version, consented_at = :consentedAt
WHERE code = :code AND keycloak_subject = :subject AND phone_number = :phone
  AND terms_version IS NULL AND consented_at IS NULL;
