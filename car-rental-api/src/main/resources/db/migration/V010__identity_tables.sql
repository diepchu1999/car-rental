-- BR-120/809, ADR-0006/0008: backend giữ tài khoản, không giữ mật khẩu, OTP hay token.
CREATE SCHEMA identity;

CREATE TABLE identity.account (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    code TEXT NOT NULL,
    keycloak_subject TEXT NOT NULL,
    phone_number TEXT NOT NULL,
    terms_version TEXT,
    consented_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_account PRIMARY KEY (id),
    CONSTRAINT uq_account_code UNIQUE (code),
    CONSTRAINT uq_account_keycloak_subject UNIQUE (keycloak_subject),
    CONSTRAINT uq_account_phone_number UNIQUE (phone_number),
    CONSTRAINT chk_account_code_format CHECK (code COLLATE "C" ~ '^TK-[A-Z0-9]{6}$'),
    CONSTRAINT chk_account_subject_not_blank CHECK (keycloak_subject ~ '[^[:space:]]'),
    -- CSDL ép dạng lưu E.164; kiểm số di động thực sự dùng metadata libphonenumber ở domain.
    CONSTRAINT chk_account_phone_format CHECK (phone_number COLLATE "C" ~ '^\+84[35789][0-9]{8}$'),
    -- Nhân viên chưa đăng ký thuê được thiếu cả cặp, không được có bằng chứng thiếu một nửa.
    CONSTRAINT chk_account_consent CHECK (
        (terms_version IS NULL AND consented_at IS NULL)
        OR (terms_version IS NOT NULL AND terms_version ~ '[^[:space:]]'
            AND consented_at IS NOT NULL AND isfinite(consented_at))
    ),
    CONSTRAINT chk_account_created_at CHECK (isfinite(created_at))
);

-- BR-803: một nhân viên chỉ thuộc một chi nhánh; lưu vai lúc gán để phát hiện lệch vai về sau.
-- Vai hiện tại vẫn do Keycloak cấp, không dùng staff_role để tự nâng quyền token.
CREATE TABLE identity.staff_branch_assignment (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    account_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    staff_role TEXT NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_staff_branch_assignment PRIMARY KEY (id),
    CONSTRAINT uq_staff_branch_account UNIQUE (account_id),
    CONSTRAINT fk_staff_branch_account FOREIGN KEY (account_id) REFERENCES identity.account(id),
    -- Chỉ FK nội bộ identity; branch_id là tham chiếu logic theo ADR-0008.
    CONSTRAINT chk_staff_branch_id CHECK (branch_id > 0),
    CONSTRAINT chk_staff_branch_role CHECK (
        staff_role IN ('BRANCH_STAFF', 'BRANCH_MANAGER', 'DELIVERY_STAFF', 'DRIVER')
    ),
    CONSTRAINT chk_staff_branch_assigned_at CHECK (isfinite(assigned_at))
);

CREATE INDEX idx_staff_branch_branch ON identity.staff_branch_assignment (branch_id);

-- BR-807: tối đa một quản lý; chi nhánh mới được chưa có quản lý, ADMIN làm thay.
CREATE UNIQUE INDEX uq_staff_branch_manager
    ON identity.staff_branch_assignment (branch_id)
    WHERE staff_role = 'BRANCH_MANAGER';
