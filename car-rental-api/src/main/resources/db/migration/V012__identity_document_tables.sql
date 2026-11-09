-- BR-120/130, ADR-0008/0015: mỗi lần nộp là một phiên bản riêng, chỉ FK trong identity.
-- Không giữ số giấy tờ dạng rõ, file nhị phân hoặc URL ký trong CSDL.
CREATE TABLE identity.document_upload (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    code TEXT NOT NULL,
    account_id BIGINT NOT NULL,
    document_type TEXT NOT NULL,
    side TEXT NOT NULL,
    incoming_object_key TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_document_upload PRIMARY KEY (id),
    CONSTRAINT uq_document_upload_code UNIQUE (code),
    CONSTRAINT uq_document_upload_object UNIQUE (incoming_object_key),
    -- Khoá ghép phục vụ FK kiểm chủ sở hữu, loại và mặt khi ảnh được gắn vào bản nộp.
    CONSTRAINT uq_document_upload_binding UNIQUE (id, account_id, document_type, side),
    CONSTRAINT fk_document_upload_account FOREIGN KEY (account_id) REFERENCES identity.account(id),
    CONSTRAINT chk_document_upload_code CHECK (code COLLATE "C" ~ '^AN-[A-Z0-9]{6}$'),
    CONSTRAINT chk_document_upload_type CHECK (document_type IN ('IDENTITY_CARD', 'DRIVER_LICENSE')),
    CONSTRAINT chk_document_upload_side CHECK (side IN ('FRONT', 'BACK')),
    CONSTRAINT chk_document_upload_object CHECK (incoming_object_key COLLATE "C"
        ~ '^incoming/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    -- Chỉ ép hình dạng; TTL 5 phút do cấu hình BR-130 quyết định, không nhúng vào DDL.
    CONSTRAINT chk_document_upload_time CHECK (
        isfinite(created_at) AND isfinite(expires_at) AND expires_at > created_at
    )
);

CREATE INDEX idx_document_upload_owner ON identity.document_upload (account_id, created_at, id);

CREATE TABLE identity.document_submission (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    code TEXT NOT NULL,
    account_id BIGINT NOT NULL,
    document_type TEXT NOT NULL,
    status TEXT NOT NULL,
    number_key_version INTEGER NOT NULL,
    number_nonce TEXT NOT NULL,
    number_ciphertext TEXT NOT NULL,
    number_hash TEXT NOT NULL,
    full_name TEXT,
    birth_date DATE,
    expires_on DATE NOT NULL,
    license_class TEXT,
    automatic_only BOOLEAN,
    submitted_at TIMESTAMPTZ NOT NULL,
    reviewed_at TIMESTAMPTZ,
    reviewer_account_id BIGINT,
    rejection_code TEXT,
    rejection_note TEXT,
    CONSTRAINT pk_document_submission PRIMARY KEY (id),
    CONSTRAINT uq_document_submission_code UNIQUE (code),
    CONSTRAINT uq_document_submission_binding UNIQUE (id, account_id, document_type),
    CONSTRAINT uq_document_submission_verified_binding UNIQUE (id, account_id, document_type, status),
    CONSTRAINT fk_document_submission_account FOREIGN KEY (account_id) REFERENCES identity.account(id),
    CONSTRAINT fk_document_submission_reviewer FOREIGN KEY (reviewer_account_id) REFERENCES identity.account(id),
    CONSTRAINT chk_document_submission_code CHECK (code COLLATE "C" ~ '^GT-[A-Z0-9]{6}$'),
    CONSTRAINT chk_document_submission_type CHECK (document_type IN ('IDENTITY_CARD', 'DRIVER_LICENSE')),
    CONSTRAINT chk_document_submission_status CHECK (status IN ('PENDING', 'VERIFIED', 'REJECTED', 'SUPERSEDED')),
    CONSTRAINT chk_document_number_key_version CHECK (number_key_version > 0),
    -- Phong bì từ adapter AES-GCM: nonce 12 byte Base64 không padding, ciphertext kèm tag.
    -- CSDL ép hình dạng; authentication tag và AAD được JCE kiểm khi đọc.
    CONSTRAINT chk_document_number_nonce CHECK (number_nonce COLLATE "C" ~ '^[A-Za-z0-9+/]{16}$'),
    CONSTRAINT chk_document_number_ciphertext CHECK (
        length(number_ciphertext) >= 24 AND length(number_ciphertext) % 4 = 0
        AND number_ciphertext COLLATE "C" ~ '^[A-Za-z0-9+/]+={0,2}$'
    ),
    CONSTRAINT chk_document_number_hash CHECK (number_hash COLLATE "C" ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_document_submission_fields CHECK (
        (document_type = 'IDENTITY_CARD'
            AND full_name IS NOT NULL AND full_name ~ '[^[:space:]]'
            AND birth_date IS NOT NULL AND isfinite(birth_date)
            AND license_class IS NULL AND automatic_only IS NULL)
        OR (document_type = 'DRIVER_LICENSE'
            AND full_name IS NULL AND birth_date IS NULL
            AND license_class IS NOT NULL AND license_class ~ '[^[:space:]]'
            AND automatic_only IS NOT NULL)
    ),
    CONSTRAINT chk_document_submission_expiry CHECK (isfinite(expires_on)),
    CONSTRAINT chk_document_submission_time CHECK (isfinite(submitted_at)),
    CONSTRAINT chk_document_submission_review CHECK (
        -- Trạng thái ngoài danh sách do chk_document_submission_status xử lý riêng.
        status NOT IN ('PENDING', 'VERIFIED', 'REJECTED', 'SUPERSEDED')
        OR (status IN ('PENDING', 'SUPERSEDED') AND reviewed_at IS NULL AND reviewer_account_id IS NULL
            AND rejection_code IS NULL AND rejection_note IS NULL)
        OR (status = 'VERIFIED' AND reviewer_account_id IS NOT NULL
            AND reviewed_at IS NOT NULL AND isfinite(reviewed_at) AND reviewed_at >= submitted_at
            AND rejection_code IS NULL AND rejection_note IS NULL)
        OR (status = 'REJECTED' AND reviewer_account_id IS NOT NULL
            AND reviewed_at IS NOT NULL AND isfinite(reviewed_at) AND reviewed_at >= submitted_at
            AND rejection_code IS NOT NULL
            AND rejection_code IN ('ILLEGIBLE', 'MISSING_SIDE', 'DATA_MISMATCH', 'EXPIRED',
                                   'HOLDER_MISMATCH', 'SUSPECTED_FORGERY')
            AND (rejection_note IS NULL OR rejection_note ~ '[^[:space:]]'))
    )
);

-- Hai request cùng nộp không được để lại hai bản PENDING cho cùng tài khoản + loại.
CREATE UNIQUE INDEX uq_document_submission_pending
    ON identity.document_submission (account_id, document_type) WHERE status = 'PENDING';
CREATE INDEX idx_document_submission_queue
    ON identity.document_submission (submitted_at, id) WHERE status = 'PENDING';
-- Cố ý KHÔNG UNIQUE: nhập số chưa xác minh không giữ chỗ số CCCD của người khác (BR-130).
-- Ràng buộc nhận số CCCD khi xác minh được bổ sung cùng luồng xác minh, không ở bước khách nhập.
CREATE INDEX idx_document_submission_number
    ON identity.document_submission (document_type, number_key_version, number_hash);

-- Một lượt tải chỉ gắn được vào một ảnh của một bản nộp; bản nộp mới phải dùng lượt tải mới.
-- Tách bảng để UNIQUE(upload_id) chặn dùng lại xuyên cả hai mặt, không chỉ trùng cùng cột FRONT/BACK.
CREATE TABLE identity.document_image (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    submission_id BIGINT NOT NULL,
    upload_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    document_type TEXT NOT NULL,
    side TEXT NOT NULL,
    sealed_object_key TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    CONSTRAINT pk_document_image PRIMARY KEY (id),
    CONSTRAINT uq_document_image_side UNIQUE (submission_id, side),
    CONSTRAINT uq_document_image_upload UNIQUE (upload_id),
    CONSTRAINT uq_document_image_object UNIQUE (sealed_object_key),
    CONSTRAINT fk_document_image_submission FOREIGN KEY (submission_id, account_id, document_type)
        REFERENCES identity.document_submission(id, account_id, document_type),
    CONSTRAINT fk_document_image_upload FOREIGN KEY (upload_id, account_id, document_type, side)
        REFERENCES identity.document_upload(id, account_id, document_type, side),
    CONSTRAINT chk_document_image_side CHECK (side IN ('FRONT', 'BACK')),
    CONSTRAINT chk_document_image_object CHECK (sealed_object_key COLLATE "C"
        ~ '^sealed/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT chk_document_image_type CHECK (content_type IN ('image/jpeg', 'image/png', 'image/heic')),
    -- Hạn byte/pixel đọc từ cấu hình khi seal ảnh; DDL chỉ ép các số đo có nghĩa.
    CONSTRAINT chk_document_image_measurements CHECK (size_bytes > 0 AND width > 0 AND height > 0)
);

CREATE TABLE identity.document_head (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    account_id BIGINT NOT NULL,
    document_type TEXT NOT NULL,
    latest_submission_id BIGINT,
    active_verified_submission_id BIGINT,
    -- Cột kỹ thuật không nhận từ caller; giúp FK ép active chỉ trỏ đúng bản VERIFIED.
    active_verified_status TEXT GENERATED ALWAYS AS ('VERIFIED'::TEXT) STORED,
    CONSTRAINT pk_document_head PRIMARY KEY (id),
    CONSTRAINT uq_document_head_owner_type UNIQUE (account_id, document_type),
    CONSTRAINT fk_document_head_account FOREIGN KEY (account_id) REFERENCES identity.account(id),
    CONSTRAINT chk_document_head_type CHECK (document_type IN ('IDENTITY_CARD', 'DRIVER_LICENSE')),
    CONSTRAINT chk_document_head_latest CHECK (
        active_verified_submission_id IS NULL OR latest_submission_id IS NOT NULL
    ),
    CONSTRAINT fk_document_head_latest FOREIGN KEY (latest_submission_id, account_id, document_type)
        REFERENCES identity.document_submission(id, account_id, document_type),
    CONSTRAINT fk_document_head_active FOREIGN KEY
        (active_verified_submission_id, account_id, document_type, active_verified_status)
        REFERENCES identity.document_submission(id, account_id, document_type, status)
);

-- BR-130: nộp/từ chối bản mới chỉ đổi latest; active chỉ đổi khi bản mới được xác minh.
-- Bản active hết hạn vẫn giữ lịch sử; cổng điều kiện thuê so expires_on với cuối chuyến, không fallback.
COMMENT ON COLUMN identity.document_head.latest_submission_id IS
    'Latest submission for progress; pending or rejected submissions do not replace the active verified version.';
COMMENT ON COLUMN identity.document_head.active_verified_submission_id IS
    'Currently selected VERIFIED submission; expiry is checked by the rental eligibility gate.';
