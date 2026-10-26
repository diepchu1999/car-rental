-- BR-130: tạo chỗ khoá cho cặp chủ/loại; không động vào con trỏ nếu đã có.
INSERT INTO identity.document_head (account_id, document_type)
VALUES ((SELECT id FROM identity.account WHERE code = :accountCode), :documentType)
ON CONFLICT ON CONSTRAINT uq_document_head_owner_type DO NOTHING;
