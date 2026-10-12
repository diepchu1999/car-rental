-- Chạy toàn bộ trong IntelliJ SQL Console, kết nối car_rental bằng car_rental_app sau V008.
-- BR-234: tạo một xe thử riêng, chứng minh từng CHECK rồi ROLLBACK toàn bộ dữ liệu thử.
-- Không chạy cùng transaction đang có công việc chưa lưu. Nếu có lỗi ngoài dự kiến, chạy ROLLBACK.
BEGIN;

DO $verification$
DECLARE
    fixture_branch_id BIGINT;
    invalid_rate BIGINT;
    approved_status TEXT;
    actual_constraint TEXT;
BEGIN
    IF EXISTS (SELECT 1 FROM vehicle.vehicle WHERE code = 'XE-T7RATE') THEN
        RAISE EXCEPTION 'Fixture XE-T7RATE already exists; no existing row will be changed.';
    END IF;

    SELECT id INTO fixture_branch_id FROM branch.branch ORDER BY id LIMIT 1;
    IF fixture_branch_id IS NULL THEN
        RAISE EXCEPTION 'Create a branch through the API before running this verification.';
    END IF;

    INSERT INTO vehicle.vehicle (
        code, plate_number, ownership_type, fuel_type, branch_id, status,
        inspection_expires_on, liability_insurance_expires_on,
        seats, transmission, make, model, daily_rate
    ) VALUES (
        'XE-T7RATE', 'T07-SQL-' || gen_random_uuid()::TEXT, 'COMPANY', 'PETROL',
        fixture_branch_id, 'DRAFT', CURRENT_DATE + 365, CURRENT_DATE + 365,
        5, 'AUTOMATIC', 'Test', 'Daily rate constraint fixture', NULL
    );

    FOREACH invalid_rate IN ARRAY ARRAY[0::BIGINT, -1000::BIGINT, 1500::BIGINT]
    LOOP
        BEGIN
            UPDATE vehicle.vehicle SET daily_rate = invalid_rate WHERE code = 'XE-T7RATE';
            RAISE EXCEPTION 'Expected chk_vehicle_daily_rate for value %.', invalid_rate;
        EXCEPTION WHEN check_violation THEN
            GET STACKED DIAGNOSTICS actual_constraint = CONSTRAINT_NAME;
            IF actual_constraint IS DISTINCT FROM 'chk_vehicle_daily_rate' THEN
                RAISE;
            END IF;
            RAISE NOTICE 'PASS: daily_rate=% -> SQLSTATE 23514, %', invalid_rate, actual_constraint;
        END;
    END LOOP;

    FOREACH approved_status IN ARRAY ARRAY['ACTIVE', 'INACTIVE', 'RETIRED']
    LOOP
        BEGIN
            UPDATE vehicle.vehicle SET status = approved_status WHERE code = 'XE-T7RATE';
            RAISE EXCEPTION 'Expected chk_vehicle_approved_has_daily_rate for status %.', approved_status;
        EXCEPTION WHEN check_violation THEN
            GET STACKED DIAGNOSTICS actual_constraint = CONSTRAINT_NAME;
            IF actual_constraint IS DISTINCT FROM 'chk_vehicle_approved_has_daily_rate' THEN
                RAISE;
            END IF;
            RAISE NOTICE 'PASS: status=% without rate -> SQLSTATE 23514, %', approved_status, actual_constraint;
        END;
    END LOOP;

    -- Giá thử tường minh, không phải giá mặc định của ứng dụng.
    UPDATE vehicle.vehicle SET daily_rate = 855000 WHERE code = 'XE-T7RATE';
    UPDATE vehicle.vehicle SET status = 'ACTIVE' WHERE code = 'XE-T7RATE';
    RAISE NOTICE 'PASS: valid daily_rate=855000 permits ACTIVE.';

    BEGIN
        UPDATE vehicle.vehicle SET daily_rate = NULL WHERE code = 'XE-T7RATE';
        RAISE EXCEPTION 'Expected chk_vehicle_approved_has_daily_rate when clearing an approved rate.';
    EXCEPTION WHEN check_violation THEN
        GET STACKED DIAGNOSTICS actual_constraint = CONSTRAINT_NAME;
        IF actual_constraint IS DISTINCT FROM 'chk_vehicle_approved_has_daily_rate' THEN
            RAISE;
        END IF;
        RAISE NOTICE 'PASS: clearing approved rate -> SQLSTATE 23514, %', actual_constraint;
    END;
END;
$verification$;

ROLLBACK;

-- Kết quả đúng: 0; xe thử không được giữ lại sau khi kiểm.
SELECT count(*) AS remaining_fixture_count
FROM vehicle.vehicle
WHERE code = 'XE-T7RATE';
