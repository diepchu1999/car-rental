-- Schema riêng của availability theo ADR-0008.
CREATE SCHEMA availability;

-- Mọi nguyên nhân khiến xe bận nằm chung một bảng theo BR-104, ADR-0005.
-- period đã bao gồm khoảng đệm của lượt thuê theo BR-109, BR-116.
-- Không có khóa ngoại sang module vehicle.
CREATE TABLE availability.reservation (
                                          id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                          code TEXT NOT NULL,
                                          vehicle_id BIGINT NOT NULL,
                                          period TSTZRANGE NOT NULL,
                                          kind TEXT NOT NULL,
                                          status TEXT NOT NULL,

    -- Một đơn có thể có nhiều reservation khi gia hạn theo BR-427.
    -- Không đặt UNIQUE trên booking_code.
                                          booking_code TEXT NULL,

    -- Lưu nguyên văn lý do khóa vận hành.
                                          reason TEXT NULL,

    -- Thời hạn do ứng dụng tính từ cấu hình theo BR-103, BR-225.
                                          hold_expires_at TIMESTAMPTZ NULL,

    -- Ứng dụng cung cấp từ Clock chung, không dùng DEFAULT now().
                                          created_at TIMESTAMPTZ NOT NULL,
                                          status_changed_at TIMESTAMPTZ NOT NULL,

                                          CONSTRAINT uq_reservation_code
                                              UNIQUE (code),

                                          CONSTRAINT chk_reservation_kind
                                              CHECK (
                                                  kind IN (
                                                           'RENTAL',
                                                           'MAINTENANCE',
                                                           'INSPECTION',
                                                           'TRANSFER',
                                                           'OWNER_BLOCK',
                                                           'COMPLIANCE_HOLD'
                                                      )
                                                  ),

                                          CONSTRAINT chk_reservation_status
                                              CHECK (
                                                  status IN (
                                                             'HELD',
                                                             'CONFIRMED',
                                                             'IN_USE',
                                                             'BLOCKED',
                                                             'RELEASED',
                                                             'COMPLETED'
                                                      )
                                                  ),

    -- Khoảng không rỗng, có cận dưới, cận dưới đóng và cận trên mở.
    -- Ép [) tại database để hai khoảng liền kề không chồng nhau.
                                          CONSTRAINT chk_period_shape
                                              CHECK (
                                                  NOT isempty(period)
                                                      AND NOT lower_inf(period)
                                                      AND lower_inc(period)
                                                      AND NOT upper_inc(period)
                                                  ),

    -- Chỉ khóa do giấy tờ hết hạn được không chặn trên theo BR-015.
                                          CONSTRAINT chk_unbounded_only_compliance
                                              CHECK (
                                                  NOT upper_inf(period)
                                                      OR kind = 'COMPLIANCE_HOLD'
                                                  ),

    -- CSDL ép có hạn và hạn sau lúc tạo.
    -- Không đóng cứng độ dài chính sách một tiếng vào CHECK.
                                          CONSTRAINT chk_hold_has_ttl
                                              CHECK (
                                                  status <> 'HELD'
                                                      OR (
                                                      hold_expires_at IS NOT NULL
                                                          AND hold_expires_at > created_at
                                                      )
                                                  ),

                                          CONSTRAINT chk_rental_has_code
                                              CHECK (
                                                  kind <> 'RENTAL'
                                                      OR booking_code IS NOT NULL
                                                  )
);

-- BR-104: cùng một xe không có hai khoảng đang chặn chồng nhau.
-- COMPLETED vẫn chặn trong period đã lưu để bảo toàn khoảng đệm.
-- RELEASED không còn chặn.
ALTER TABLE availability.reservation
    ADD CONSTRAINT reservation_no_overlap
        EXCLUDE USING gist (
        vehicle_id WITH =,
        period WITH &&
        )
        WHERE (
            status IN (
                       'HELD',
                       'CONFIRMED',
                       'IN_USE',
                       'BLOCKED',
                       'COMPLETED'
                )
            );

-- Phục vụ truy vấn lịch theo xe và khoảng thời gian.
CREATE INDEX idx_reservation_vehicle_period
    ON availability.reservation USING gist (vehicle_id, period);

-- Phục vụ job tìm chỗ giữ quá hạn theo BR-103.
CREATE INDEX idx_reservation_hold_expiry
    ON availability.reservation (hold_expires_at)
    WHERE status = 'HELD';