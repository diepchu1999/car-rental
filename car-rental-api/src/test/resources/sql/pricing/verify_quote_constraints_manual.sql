-- Chạy toàn bộ file trong IntelliJ SQL Console riêng, user car_rental_app, sau V009.
-- BR-122/234/236, ADR-0008/0014: thử phá từng CHECK và UPDATE snapshot, rồi ROLLBACK.
-- Phí 100000 chỉ là dữ liệu thử. Không chạy trong transaction có công việc chưa lưu.
-- Nếu có lỗi ngoài dự kiến, chạy riêng ROLLBACK trước khi thử lại.
BEGIN;

DO $verification$
DECLARE
    base_code TEXT := 'BG-' || to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYMM') || '-T7SQL1';
    bad_code TEXT := 'BG-' || to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYMM') || '-T7SQL2';
    chosen_vehicle TEXT;
    chosen_rate BIGINT;
    created TIMESTAMPTZ := clock_timestamp();
    fixture_quote_id BIGINT;
    candidate pricing.quote%ROWTYPE;
    candidate_line pricing.quote_line%ROWTYPE;
    test_case RECORD;
    actual_constraint TEXT;
    actual_state TEXT;
BEGIN
    IF EXISTS (SELECT 1 FROM pricing.quote WHERE code IN (base_code, bad_code)) THEN
        RAISE EXCEPTION 'Manual quote fixture already exists; no existing row will be changed.';
    END IF;
    SELECT code, daily_rate INTO chosen_vehicle, chosen_rate FROM vehicle.vehicle
    WHERE status = 'ACTIVE' ORDER BY daily_rate, code LIMIT 1;
    IF chosen_vehicle IS NULL THEN
        RAISE EXCEPTION 'Run the vehicle Postman setup to create one ACTIVE vehicle before this verification.';
    END IF;
    INSERT INTO pricing.quote (
        code, vehicle_code, start_at, end_at, rental_type, drive_mode, pickup_method,
        daily_rate, weekend_days, weekend_uplift, holiday_uplift,
        insurance_calculation, insurance_value, insurance_billing_days, quote_validity_seconds,
        rental_amount, insurance_fee, total_amount, created_at, expires_at, expires_at_submicro_nanos
    ) VALUES (
        base_code, chosen_vehicle, '2026-11-10 03:00:00+00', '2026-11-11 03:00:00+00',
        'DAILY', 'SELF_DRIVE', 'BRANCH', chosen_rate, ARRAY['SATURDAY', 'SUNDAY'], 0.2, 0.5,
        'PER_BILLING_DAY', 100000, 1, 900, chosen_rate, 100000, chosen_rate + 100000,
        created, created + INTERVAL '15 minutes', 0
    ) RETURNING id INTO fixture_quote_id;
    INSERT INTO pricing.quote_line (quote_id, line_number, type, day_type, start_at, hours, daily_rate, uplift, amount)
    VALUES (fixture_quote_id, 1, 'DAY_BLOCK', 'WEEKDAY', '2026-11-10 03:00:00+00', 24, chosen_rate, 0, chosen_rate);

    -- Mỗi hàng đổi đúng một trường trên bản sao, không UPDATE header đang tồn tại.
    FOR test_case IN SELECT * FROM (VALUES
        ('code', '"invalid"'::JSONB, 'chk_quote_code_format'),
        ('vehicle_code', '"123"'::JSONB, 'chk_quote_vehicle_code'),
        ('end_at', '"2026-11-10 03:00:00+00"'::JSONB, 'chk_quote_period'),
        ('rental_type', '"MONTHLY"'::JSONB, 'chk_quote_rental_type'),
        ('drive_mode', '"WITH_DRIVER"'::JSONB, 'chk_quote_drive_mode'),
        ('pickup_method', '"DELIVERY"'::JSONB, 'chk_quote_pickup_method'),
        ('daily_rate', '1500'::JSONB, 'chk_quote_daily_rate'),
        ('weekend_days', '[]'::JSONB, 'chk_quote_weekend_days'),
        ('weekend_uplift', '-0.1'::JSONB, 'chk_quote_uplifts'),
        ('holiday_uplift', '"NaN"'::JSONB, 'chk_quote_uplifts'),
        ('insurance_value', '0'::JSONB, 'chk_quote_insurance'),
        ('insurance_value', '100000.1'::JSONB, 'chk_quote_insurance'),
        ('insurance_fee', '1500'::JSONB, 'chk_quote_amounts'),
        ('quote_validity_seconds', '0'::JSONB, 'chk_quote_expiry')
    ) AS cases(field_name, new_value, expected_constraint)
    LOOP
        BEGIN
            SELECT transformed.* INTO candidate FROM pricing.quote q
            CROSS JOIN LATERAL jsonb_populate_record(NULL::pricing.quote,
                to_jsonb(q) || jsonb_build_object('code', bad_code)
                || jsonb_build_object(test_case.field_name, test_case.new_value)) transformed
            WHERE q.code = base_code;
            INSERT INTO pricing.quote (
                code, vehicle_code, start_at, end_at, rental_type, drive_mode, pickup_method,
                daily_rate, weekend_days, weekend_uplift, holiday_uplift,
                insurance_calculation, insurance_value, insurance_billing_days, quote_validity_seconds,
                rental_amount, insurance_fee, total_amount, created_at, expires_at, expires_at_submicro_nanos
            ) VALUES (
                candidate.code, candidate.vehicle_code, candidate.start_at, candidate.end_at,
                candidate.rental_type, candidate.drive_mode, candidate.pickup_method,
                candidate.daily_rate, candidate.weekend_days, candidate.weekend_uplift, candidate.holiday_uplift,
                candidate.insurance_calculation, candidate.insurance_value, candidate.insurance_billing_days,
                candidate.quote_validity_seconds, candidate.rental_amount, candidate.insurance_fee,
                candidate.total_amount, candidate.created_at, candidate.expires_at, candidate.expires_at_submicro_nanos
            );
            RAISE EXCEPTION 'Expected constraint %.', test_case.expected_constraint;
        EXCEPTION WHEN check_violation THEN
            GET STACKED DIAGNOSTICS actual_constraint = CONSTRAINT_NAME, actual_state = RETURNED_SQLSTATE;
            IF actual_constraint IS DISTINCT FROM test_case.expected_constraint THEN RAISE; END IF;
            RAISE NOTICE 'PASS: % / %', actual_state, actual_constraint;
        END;
    END LOOP;

    FOR test_case IN SELECT * FROM (VALUES
        ('line_number', '0'::JSONB, 'chk_quote_line_position'),
        ('hours', '25'::JSONB, 'chk_quote_line_type_hours'),
        ('day_type', '"UNKNOWN"'::JSONB, 'chk_quote_line_day'),
        ('start_at', '"infinity"'::JSONB, 'chk_quote_line_start'),
        ('daily_rate', '1500'::JSONB, 'chk_quote_line_rate'),
        ('amount', '1500'::JSONB, 'chk_quote_line_amount')
    ) AS cases(field_name, new_value, expected_constraint)
    LOOP
        BEGIN
            SELECT transformed.* INTO candidate_line FROM pricing.quote_line line
            CROSS JOIN LATERAL jsonb_populate_record(NULL::pricing.quote_line,
                to_jsonb(line) || jsonb_build_object('line_number', 2)
                || jsonb_build_object(test_case.field_name, test_case.new_value)) transformed
            WHERE line.quote_id = fixture_quote_id AND line.line_number = 1;
            INSERT INTO pricing.quote_line (quote_id, line_number, type, day_type, start_at, hours, daily_rate, uplift, amount)
            VALUES (candidate_line.quote_id, candidate_line.line_number, candidate_line.type, candidate_line.day_type,
                candidate_line.start_at, candidate_line.hours, candidate_line.daily_rate, candidate_line.uplift, candidate_line.amount);
            RAISE EXCEPTION 'Expected constraint %.', test_case.expected_constraint;
        EXCEPTION WHEN check_violation THEN
            GET STACKED DIAGNOSTICS actual_constraint = CONSTRAINT_NAME, actual_state = RETURNED_SQLSTATE;
            IF actual_constraint IS DISTINCT FROM test_case.expected_constraint THEN RAISE; END IF;
            RAISE NOTICE 'PASS: % / %', actual_state, actual_constraint;
        END;
    END LOOP;

    BEGIN
        UPDATE pricing.quote SET total_amount = total_amount WHERE code = base_code;
        RAISE EXCEPTION 'Expected chk_quote_immutable.';
    EXCEPTION WHEN check_violation THEN
        GET STACKED DIAGNOSTICS actual_constraint = CONSTRAINT_NAME;
        IF actual_constraint IS DISTINCT FROM 'chk_quote_immutable' THEN RAISE; END IF;
        RAISE NOTICE 'PASS: 23514 / %', actual_constraint;
    END;
    BEGIN
        UPDATE pricing.quote_line SET amount = amount WHERE pricing.quote_line.quote_id = fixture_quote_id;
        RAISE EXCEPTION 'Expected chk_quote_line_immutable.';
    EXCEPTION WHEN check_violation THEN
        GET STACKED DIAGNOSTICS actual_constraint = CONSTRAINT_NAME;
        IF actual_constraint IS DISTINCT FROM 'chk_quote_line_immutable' THEN RAISE; END IF;
        RAISE NOTICE 'PASS: 23514 / %', actual_constraint;
    END;
END;
$verification$;
ROLLBACK;

-- Kết quả đúng: 0; mọi header và dòng thử đều được rollback.
SELECT count(*) AS remaining_fixture_count FROM pricing.quote
WHERE code IN (
    'BG-' || to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYMM') || '-T7SQL1',
    'BG-' || to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYMM') || '-T7SQL2'
);
