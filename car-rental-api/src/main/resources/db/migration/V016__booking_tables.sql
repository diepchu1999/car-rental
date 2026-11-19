-- BR-102/103/122/131/201, ADR-0008/0014: đơn giữ bản sao độc lập của giá và điều kiện thuê.
-- Chỉ FK nội bộ booking; không ghi hoặc thay đổi bảng availability ở migration này.
CREATE SCHEMA booking;

CREATE TABLE booking.booking (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    code TEXT NOT NULL,
    customer_account_code TEXT NOT NULL,
    quote_code TEXT NOT NULL,
    vehicle_code TEXT NOT NULL,
    branch_code TEXT NOT NULL,
    reservation_code TEXT NOT NULL,
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    rental_type TEXT NOT NULL,
    drive_mode TEXT NOT NULL,
    pickup_method TEXT NOT NULL,
    daily_rate BIGINT NOT NULL,
    insurance_calculation TEXT NOT NULL,
    insurance_value NUMERIC NOT NULL,
    insurance_billing_days INTEGER NOT NULL,
    rental_amount BIGINT NOT NULL,
    insurance_fee BIGINT NOT NULL,
    total_amount BIGINT NOT NULL,
    deposit_ratio NUMERIC NOT NULL,
    deposit BIGINT NOT NULL,
    balance BIGINT NOT NULL,
    turnaround_buffer_seconds NUMERIC NOT NULL,
    minimum_duration_seconds NUMERIC,
    branch_opens_at TIME NOT NULL,
    branch_closes_at TIME NOT NULL,
    minimum_advance_seconds NUMERIC NOT NULL,
    maximum_advance_months INTEGER NOT NULL,
    hold_expires_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    status_changed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_booking PRIMARY KEY (id),
    CONSTRAINT uq_booking_code UNIQUE (code),
    CONSTRAINT uq_booking_reservation_code UNIQUE (reservation_code),
    CONSTRAINT chk_booking_code CHECK (code COLLATE "C" ~ '^DT-[0-9]{2}(0[1-9]|1[0-2])-[A-Z0-9]{6}$'),
    CONSTRAINT chk_booking_customer CHECK (customer_account_code COLLATE "C" ~ '^TK-[A-Z0-9]{6}$'),
    CONSTRAINT chk_booking_quote CHECK (quote_code COLLATE "C" ~ '^BG-[0-9]{2}(0[1-9]|1[0-2])-[A-Z0-9]{6}$'),
    CONSTRAINT chk_booking_vehicle CHECK (vehicle_code COLLATE "C" ~ '^XE-[A-Z0-9]{6}$'),
    CONSTRAINT chk_booking_branch CHECK (branch_code COLLATE "C" ~ '^CN-[A-Z0-9]{6}$'),
    CONSTRAINT chk_booking_reservation CHECK (reservation_code COLLATE "C" ~ '^KL-[A-Z0-9]{6}$'),
    CONSTRAINT chk_booking_period CHECK (isfinite(start_at) AND isfinite(end_at) AND end_at > start_at),
    CONSTRAINT chk_booking_rental_type CHECK (rental_type IN ('HOURLY', 'DAILY')),
    CONSTRAINT chk_booking_drive_mode CHECK (drive_mode = 'SELF_DRIVE'),
    CONSTRAINT chk_booking_pickup_method CHECK (pickup_method = 'BRANCH'),
    CONSTRAINT chk_booking_daily_rate CHECK (daily_rate > 0 AND daily_rate % 1000 = 0),
    CONSTRAINT chk_booking_insurance CHECK (
        insurance_calculation IN ('PER_BILLING_DAY', 'PERCENT_OF_RENT')
        AND insurance_value > 0 AND insurance_value < 'Infinity'::NUMERIC AND insurance_billing_days > 0
        AND (insurance_calculation <> 'PER_BILLING_DAY' OR insurance_value = trunc(insurance_value))
    ),
    -- Không đóng cứng tỷ lệ 30% ở CHECK: tỷ lệ đã áp được lưu riêng, đơn cũ không đọc cấu hình mới.
    CONSTRAINT chk_booking_amounts CHECK (
        rental_amount > 0 AND rental_amount % 1000 = 0 AND insurance_fee >= 0 AND insurance_fee % 1000 = 0
        AND total_amount = rental_amount::NUMERIC + insurance_fee::NUMERIC
        AND deposit_ratio > 0 AND deposit_ratio <= 1
        AND deposit >= 0 AND balance >= 0
        AND deposit = round(total_amount::NUMERIC * deposit_ratio / 1000) * 1000
        AND total_amount = deposit::NUMERIC + balance::NUMERIC
    ),
    -- Duration lưu số giây thập phân, tối đa nano giây; tháng lịch lưu riêng, không đổi thành 180 ngày.
    CONSTRAINT chk_booking_terms CHECK (
        turnaround_buffer_seconds > 0 AND turnaround_buffer_seconds < 'Infinity'::NUMERIC
        AND turnaround_buffer_seconds * 1000000000 = trunc(turnaround_buffer_seconds * 1000000000)
        AND (minimum_duration_seconds IS NULL OR (
            minimum_duration_seconds > 0 AND minimum_duration_seconds < 'Infinity'::NUMERIC
            AND minimum_duration_seconds * 1000000000 = trunc(minimum_duration_seconds * 1000000000)))
        AND branch_opens_at < branch_closes_at AND branch_closes_at < TIME '24:00:00'
        AND minimum_advance_seconds > 0 AND minimum_advance_seconds < 'Infinity'::NUMERIC
        AND minimum_advance_seconds * 1000000000 = trunc(minimum_advance_seconds * 1000000000)
        AND maximum_advance_months > 0
    ),
    CONSTRAINT chk_booking_status CHECK (status IN ('PENDING_PAYMENT', 'CANCELLED')),
    CONSTRAINT chk_booking_timestamps CHECK (
        isfinite(created_at) AND isfinite(status_changed_at) AND isfinite(hold_expires_at)
        AND hold_expires_at > created_at AND status_changed_at >= created_at
    )
);

-- BR-131: hai INSERT đồng thời cùng khách vẫn chỉ có một đơn chờ. Không dùng kiểm tra đọc-rồi-ghi.
CREATE UNIQUE INDEX uq_booking_pending_customer ON booking.booking (customer_account_code)
    WHERE status = 'PENDING_PAYMENT';
CREATE INDEX idx_booking_customer_created ON booking.booking (customer_account_code, created_at DESC, code DESC);
CREATE INDEX idx_booking_pending_expiry ON booking.booking (hold_expires_at, code) WHERE status = 'PENDING_PAYMENT';

CREATE TABLE booking.price_line (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    booking_id BIGINT NOT NULL,
    line_number INTEGER NOT NULL,
    type TEXT NOT NULL,
    day_type TEXT NOT NULL,
    start_at TIMESTAMPTZ NOT NULL,
    hours INTEGER NOT NULL,
    daily_rate BIGINT NOT NULL,
    uplift NUMERIC NOT NULL,
    amount BIGINT NOT NULL,
    CONSTRAINT pk_booking_price_line PRIMARY KEY (id),
    CONSTRAINT fk_booking_price_line_booking FOREIGN KEY (booking_id) REFERENCES booking.booking(id),
    CONSTRAINT uq_booking_price_line_position UNIQUE (booking_id, line_number),
    CONSTRAINT chk_booking_price_line_position CHECK (line_number > 0),
    CONSTRAINT chk_booking_price_line_type CHECK (
        (type = 'DAY_BLOCK' AND hours = 24) OR (type = 'HOURLY_PACKAGE' AND hours = 4)
        OR (type IN ('REMAINDER_HOURS', 'OVERAGE_HOURS') AND hours BETWEEN 1 AND 4)
    ),
    CONSTRAINT chk_booking_price_line_day CHECK (
        day_type IN ('WEEKDAY', 'WEEKEND', 'HOLIDAY') AND uplift >= 0 AND uplift < 'Infinity'::NUMERIC
        AND (day_type <> 'WEEKDAY' OR uplift = 0)
    ),
    CONSTRAINT chk_booking_price_line_start CHECK (isfinite(start_at)),
    CONSTRAINT chk_booking_price_line_rate CHECK (daily_rate > 0 AND daily_rate % 1000 = 0),
    CONSTRAINT chk_booking_price_line_amount CHECK (amount >= 0 AND amount % 1000 = 0)
);

-- status-flow §1: lưu ai, lúc nào, lý do; không UPDATE hoặc DELETE lịch sử.
CREATE TABLE booking.status_history (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    booking_id BIGINT NOT NULL,
    from_status TEXT,
    to_status TEXT NOT NULL,
    actor_account_code TEXT,
    reason TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_booking_status_history PRIMARY KEY (id),
    CONSTRAINT fk_booking_history_booking FOREIGN KEY (booking_id) REFERENCES booking.booking(id),
    CONSTRAINT uq_booking_history_destination UNIQUE (booking_id, to_status),
    CONSTRAINT chk_booking_history_actor CHECK (
        actor_account_code IS NULL OR actor_account_code COLLATE "C" ~ '^TK-[A-Z0-9]{6}$'
    ),
    CONSTRAINT chk_booking_history_transition CHECK (
        (from_status IS NULL AND to_status = 'PENDING_PAYMENT' AND reason = 'CREATED' AND actor_account_code IS NOT NULL)
        OR (from_status IS NOT NULL AND from_status = 'PENDING_PAYMENT' AND to_status = 'CANCELLED'
            AND ((reason = 'CUSTOMER_CANCELLED' AND actor_account_code IS NOT NULL)
                OR (reason = 'HOLD_EXPIRED' AND actor_account_code IS NULL)))
    ),
    CONSTRAINT chk_booking_history_timestamp CHECK (isfinite(occurred_at))
);

-- api-guideline §5: một key trong phạm vi khách; truy vấn phát lại sẽ bỏ qua key hết 24 giờ.
-- FK hoãn tới commit cho phép chiếm key trước khi giữ chỗ/tạo đơn; không thể commit key mồ côi.
-- quote_code là toàn bộ nội dung nghiệp vụ của request tạo đơn, không cần lưu body/token.
CREATE TABLE booking.idempotency (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    customer_account_code TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    quote_code TEXT NOT NULL,
    booking_code TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_booking_idempotency PRIMARY KEY (id),
    CONSTRAINT uq_booking_idempotency_customer_key UNIQUE (customer_account_code, idempotency_key),
    CONSTRAINT fk_booking_idempotency_booking FOREIGN KEY (booking_code) REFERENCES booking.booking(code)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT chk_booking_idempotency_customer CHECK (customer_account_code COLLATE "C" ~ '^TK-[A-Z0-9]{6}$'),
    CONSTRAINT chk_booking_idempotency_key CHECK (length(btrim(idempotency_key)) > 0),
    CONSTRAINT chk_booking_idempotency_quote CHECK (quote_code COLLATE "C" ~ '^BG-[0-9]{2}(0[1-9]|1[0-2])-[A-Z0-9]{6}$'),
    CONSTRAINT chk_booking_idempotency_expiry CHECK (isfinite(created_at) AND isfinite(expires_at) AND expires_at > created_at)
);

-- Chỉ trạng thái/mốc trạng thái được đổi; thêm snapshot quyết toán về sau bằng migration mới (ADR-0014).
CREATE FUNCTION booking.protect_snapshot() RETURNS TRIGGER LANGUAGE plpgsql AS $function$
BEGIN
    IF (to_jsonb(NEW) - ARRAY['status', 'status_changed_at'])
            IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status', 'status_changed_at']) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Frozen booking values cannot be updated.',
            SCHEMA = TG_TABLE_SCHEMA, TABLE = TG_TABLE_NAME, CONSTRAINT = 'chk_booking_snapshot_immutable';
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_booking_snapshot_immutable BEFORE UPDATE ON booking.booking
    FOR EACH ROW EXECUTE FUNCTION booking.protect_snapshot();

CREATE FUNCTION booking.protect_evidence_row() RETURNS TRIGGER LANGUAGE plpgsql AS $function$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Booking price lines and history are immutable.',
        SCHEMA = TG_TABLE_SCHEMA, TABLE = TG_TABLE_NAME,
        CONSTRAINT = CASE WHEN TG_TABLE_NAME = 'price_line'
            THEN 'chk_booking_price_line_immutable' ELSE 'chk_booking_history_immutable' END;
END;
$function$;
CREATE TRIGGER trg_booking_price_line_immutable BEFORE UPDATE OR DELETE ON booking.price_line
    FOR EACH ROW EXECUTE FUNCTION booking.protect_evidence_row();
CREATE TRIGGER trg_booking_history_immutable BEFORE UPDATE OR DELETE ON booking.status_history
    FOR EACH ROW EXECUTE FUNCTION booking.protect_evidence_row();
