-- BR-122, ADR-0008: chỉ lưu mã chủ, không FK xuyên schema và không đoán chủ của báo giá cũ.
ALTER TABLE pricing.quote ADD COLUMN customer_account_code TEXT;

ALTER TABLE pricing.quote ADD CONSTRAINT chk_quote_customer_account_code
    CHECK (customer_account_code COLLATE "C" ~ '^TK-[A-Z0-9]{6}$');

-- NOT VALID giữ nguyên các bản cũ NULL; PostgreSQL vẫn ép CHECK cho mọi INSERT mới.
-- Không VALIDATE cho tới khi có chính sách xử lý lịch sử; trigger bất biến V009 vẫn giữ nguyên.
ALTER TABLE pricing.quote ADD CONSTRAINT chk_quote_owner_required
    CHECK (customer_account_code IS NOT NULL) NOT VALID;
