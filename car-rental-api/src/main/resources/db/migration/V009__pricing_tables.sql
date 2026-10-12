-- BR-122, ADR-0008/0014: báo giá bất biến, snapshot không chứa toàn bộ lịch lễ.
CREATE SCHEMA pricing;

CREATE TABLE pricing.quote (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    code TEXT NOT NULL,
    vehicle_code TEXT NOT NULL,
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    rental_type TEXT NOT NULL,
    drive_mode TEXT NOT NULL,
    pickup_method TEXT NOT NULL,
    daily_rate BIGINT NOT NULL,
    weekend_days TEXT[] NOT NULL,
    weekend_uplift NUMERIC NOT NULL,
    holiday_uplift NUMERIC NOT NULL,
    insurance_calculation TEXT NOT NULL,
    insurance_value NUMERIC NOT NULL,
    insurance_billing_days INTEGER NOT NULL,
    quote_validity_seconds NUMERIC NOT NULL,
    rental_amount BIGINT NOT NULL,
    insurance_fee BIGINT NOT NULL,
    total_amount BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    -- PostgreSQL giữ tới micro giây; phần nano còn lại bảo toàn hạn cấu hình nhỏ hơn một micro giây.
    expires_at_submicro_nanos INTEGER NOT NULL,
    CONSTRAINT pk_quote PRIMARY KEY (id),
    CONSTRAINT uq_quote_code UNIQUE (code),
    CONSTRAINT chk_quote_code_format CHECK (code COLLATE "C" ~ '^BG-[0-9]{2}(0[1-9]|1[0-2])-[A-Z0-9]{6}$'),
    CONSTRAINT chk_quote_vehicle_code CHECK (vehicle_code COLLATE "C" ~ '^XE-[A-Z0-9]{6}$'),
    CONSTRAINT chk_quote_period CHECK (isfinite(start_at) AND isfinite(end_at) AND end_at > start_at),
    CONSTRAINT chk_quote_rental_type CHECK (rental_type IN ('HOURLY', 'DAILY')),
    CONSTRAINT chk_quote_drive_mode CHECK (drive_mode = 'SELF_DRIVE'),
    CONSTRAINT chk_quote_pickup_method CHECK (pickup_method = 'BRANCH'),
    CONSTRAINT chk_quote_daily_rate CHECK (daily_rate > 0 AND daily_rate % 1000 = 0),
    CONSTRAINT chk_quote_weekend_days CHECK (
        cardinality(weekend_days) > 0 AND array_position(weekend_days, NULL) IS NULL
        AND weekend_days <@ ARRAY['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']::TEXT[]
    ),
    CONSTRAINT chk_quote_uplifts CHECK (
        weekend_uplift >= 0 AND weekend_uplift < 'Infinity'::NUMERIC
        AND holiday_uplift >= 0 AND holiday_uplift < 'Infinity'::NUMERIC
    ),
    CONSTRAINT chk_quote_insurance CHECK (
        insurance_calculation IN ('PER_BILLING_DAY', 'PERCENT_OF_RENT') AND insurance_value > 0
        AND insurance_value < 'Infinity'::NUMERIC AND insurance_billing_days > 0
        AND (insurance_calculation <> 'PER_BILLING_DAY' OR insurance_value = trunc(insurance_value))
    ),
    CONSTRAINT chk_quote_amounts CHECK (
        rental_amount > 0 AND rental_amount % 1000 = 0
        AND insurance_fee >= 0 AND insurance_fee % 1000 = 0
        AND total_amount = rental_amount::NUMERIC + insurance_fee::NUMERIC
    ),
    CONSTRAINT chk_quote_expiry CHECK (
        quote_validity_seconds > 0 AND quote_validity_seconds < 'Infinity'::NUMERIC
        AND quote_validity_seconds * 1000000000 = trunc(quote_validity_seconds * 1000000000)
        AND isfinite(created_at) AND isfinite(expires_at)
        AND expires_at_submicro_nanos BETWEEN 0 AND 999
        AND (expires_at > created_at OR (expires_at = created_at AND expires_at_submicro_nanos > 0))
        AND extract(epoch FROM (expires_at - created_at))
            + expires_at_submicro_nanos::NUMERIC / 1000000000 = quote_validity_seconds
    )
);

-- Chỉ FK trong cùng module; vehicle_code không FK chéo schema (ADR-0008).
CREATE TABLE pricing.quote_line (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    quote_id BIGINT NOT NULL,
    line_number INTEGER NOT NULL,
    type TEXT NOT NULL,
    day_type TEXT NOT NULL,
    start_at TIMESTAMPTZ NOT NULL,
    hours INTEGER NOT NULL,
    daily_rate BIGINT NOT NULL,
    uplift NUMERIC NOT NULL,
    amount BIGINT NOT NULL,
    CONSTRAINT pk_quote_line PRIMARY KEY (id),
    CONSTRAINT fk_quote_line_quote FOREIGN KEY (quote_id) REFERENCES pricing.quote(id),
    CONSTRAINT uq_quote_line_position UNIQUE (quote_id, line_number),
    CONSTRAINT chk_quote_line_position CHECK (line_number > 0),
    CONSTRAINT chk_quote_line_type_hours CHECK (
        (type = 'DAY_BLOCK' AND hours = 24)
        OR (type = 'REMAINDER_HOURS' AND hours BETWEEN 1 AND 4)
        OR (type = 'HOURLY_PACKAGE' AND hours = 4)
        OR (type = 'OVERAGE_HOURS' AND hours BETWEEN 1 AND 4)
    ),
    CONSTRAINT chk_quote_line_day CHECK (
        day_type IN ('WEEKDAY', 'WEEKEND', 'HOLIDAY') AND uplift >= 0 AND uplift < 'Infinity'::NUMERIC
        AND (day_type <> 'WEEKDAY' OR uplift = 0)
    ),
    CONSTRAINT chk_quote_line_start CHECK (isfinite(start_at)),
    CONSTRAINT chk_quote_line_rate CHECK (daily_rate > 0 AND daily_rate % 1000 = 0),
    CONSTRAINT chk_quote_line_amount CHECK (amount >= 0 AND amount % 1000 = 0)
);

-- BR-122: không sửa nội dung đã chốt, kể cả qua SQL tay. Không quyết định chính sách xóa/lưu trữ.
CREATE FUNCTION pricing.prevent_quote_update() RETURNS TRIGGER LANGUAGE plpgsql AS $function$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Saved quote values cannot be updated.',
        SCHEMA = TG_TABLE_SCHEMA, TABLE = TG_TABLE_NAME,
        CONSTRAINT = CASE WHEN TG_TABLE_NAME = 'quote' THEN 'chk_quote_immutable' ELSE 'chk_quote_line_immutable' END;
END;
$function$;

CREATE TRIGGER trg_quote_immutable BEFORE UPDATE ON pricing.quote
    FOR EACH ROW EXECUTE FUNCTION pricing.prevent_quote_update();
CREATE TRIGGER trg_quote_line_immutable BEFORE UPDATE ON pricing.quote_line
    FOR EACH ROW EXECUTE FUNCTION pricing.prevent_quote_update();
