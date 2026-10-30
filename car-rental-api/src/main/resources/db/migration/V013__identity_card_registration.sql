-- BR-130: chỉ nhận số CCCD khi xác minh; bảng này không lưu số rõ và không có mã công khai riêng.
CREATE TABLE identity.identity_card_registration (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    number_hash TEXT NOT NULL,
    account_id BIGINT NOT NULL,
    CONSTRAINT pk_identity_card_registration PRIMARY KEY (id),
    CONSTRAINT uq_identity_card_registration_hash UNIQUE (number_hash),
    CONSTRAINT uq_identity_card_registration_binding UNIQUE (number_hash, account_id),
    CONSTRAINT fk_identity_card_registration_account FOREIGN KEY (account_id) REFERENCES identity.account(id),
    CONSTRAINT chk_identity_card_registration_hash CHECK (number_hash COLLATE "C" ~ '^[0-9a-f]{64}$')
);

-- Dữ liệu VERIFIED từ trước phải nhất quán; không chọn tùy tiện một tài khoản hoặc in hash vào lỗi.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM identity.document_submission
        WHERE document_type = 'IDENTITY_CARD' AND status = 'VERIFIED'
        GROUP BY number_hash HAVING count(DISTINCT account_id) > 1
    ) THEN
        RAISE EXCEPTION 'Existing verified identity cards have conflicting owners; resolve before migration.';
    END IF;
END $$;

INSERT INTO identity.identity_card_registration (number_hash, account_id)
SELECT DISTINCT number_hash, account_id FROM identity.document_submission
WHERE document_type = 'IDENTITY_CARD' AND status = 'VERIFIED';

-- Cột sinh không cho người gọi tự điền NULL để vượt FK; pending/GPLX không giữ chỗ CCCD.
ALTER TABLE identity.document_submission
    ADD COLUMN verified_identity_number_hash TEXT GENERATED ALWAYS AS (
        CASE WHEN document_type = 'IDENTITY_CARD' AND status = 'VERIFIED' THEN number_hash END
    ) STORED,
    ADD CONSTRAINT fk_document_verified_identity_registration
        FOREIGN KEY (verified_identity_number_hash, account_id)
        REFERENCES identity.identity_card_registration(number_hash, account_id);

-- Hash dùng khoá HMAC hiện hành. Không chia UNIQUE theo key version: đổi phiên bản số không được
-- tạo thêm chủ cho cùng hash. Đổi khoá HMAC thực sự cần migration/re-hash riêng, chưa hỗ trợ tự xoay khoá.
COMMENT ON TABLE identity.identity_card_registration IS
    'Verified identity-card ownership; pending submissions never reserve a number. HMAC rotation requires coordinated migration.';
