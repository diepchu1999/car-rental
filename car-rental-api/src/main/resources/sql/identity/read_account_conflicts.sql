-- Statement riêng sau INSERT: snapshot READ COMMITTED mới nhìn thấy transaction vừa thắng.
SELECT EXISTS (SELECT 1 FROM identity.account WHERE keycloak_subject = :subject) AS subject_exists,
       EXISTS (SELECT 1 FROM identity.account WHERE phone_number = :phone) AS phone_exists,
       EXISTS (SELECT 1 FROM identity.account WHERE code = :code) AS code_exists;
