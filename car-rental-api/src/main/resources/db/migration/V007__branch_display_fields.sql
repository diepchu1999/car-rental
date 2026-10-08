-- BR-808: tên và địa chỉ bắt buộc, không tự điền thông tin phỏng đoán.
-- Kiểm và xử lý dữ liệu mẫu local trước khi áp dụng migration.
ALTER TABLE branch.branch
    ADD COLUMN name TEXT NOT NULL,
    ADD COLUMN address TEXT NOT NULL,
    ADD CONSTRAINT chk_branch_name_not_blank CHECK (name ~ '[^[:space:]]'),
    ADD CONSTRAINT chk_branch_address_not_blank CHECK (address ~ '[^[:space:]]');
