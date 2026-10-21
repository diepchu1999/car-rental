-- Chỉ chạy thủ công trên database local car_rental, bằng SQL Console IntelliJ.
-- BR-104/113/116: chuyến mẫu gói giờ 06:00–10:00, đệm một giờ, lưu [06:00,11:00).
-- Chỉ mô phỏng khoảng đã lưu để kiểm search; việc policy tính đệm có test tự động riêng.
-- Fixture cố định của lượt nghiệm thu Task 6, không phải migration hoặc SQL production.
-- CONFIRMED tránh job dọn HELD làm thay đổi fixture trong lúc kiểm thủ công.
-- Chạy toàn bộ file khi console không có transaction dở. Nếu lỗi: ROLLBACK và báo lại.
-- Phải COMMIT để backend nhìn thấy fixture qua kết nối riêng.
BEGIN;

DO $fixture$
DECLARE
    target_vehicle_id BIGINT;
    fixture_period TSTZRANGE := tstzrange(
        TIMESTAMPTZ '2026-10-22 06:00:00+07',
        TIMESTAMPTZ '2026-10-22 11:00:00+07',
        '[)'
    );
BEGIN
    IF current_database() <> 'car_rental' THEN
        RAISE EXCEPTION 'This fixture is intended only for the local car_rental database.';
    END IF;

    SELECT v.id INTO STRICT target_vehicle_id
    FROM vehicle.vehicle AS v
    JOIN branch.branch AS b ON b.id = v.branch_id
    WHERE v.code = 'XE-QWFS1I'
      AND v.model = 'T06-Search-ff9d534b-d760-4e90-b87d-2d0ffac26799'
      AND v.status = 'ACTIVE'
      AND b.code = 'CN-QNKBRE';

    -- Chạy lại chỉ bỏ qua bản ghi đúng nguyên trạng, không ghi đè khi trùng mã.
    IF EXISTS (
        SELECT 1 FROM availability.reservation WHERE code = 'KL-T8M003'
    ) THEN
        IF NOT EXISTS (
            SELECT 1
            FROM availability.reservation
            WHERE code = 'KL-T8M003'
              AND vehicle_id = target_vehicle_id
              AND period = fixture_period
              AND kind = 'RENTAL'
              AND status = 'CONFIRMED'
              AND booking_code = 'TASK06-MANUAL-ff9d534b-HOURLY'
              AND reason = 'Task 6 manual hourly fixture ff9d534b'
              AND hold_expires_at IS NULL
        ) THEN
            RAISE EXCEPTION 'Fixture code KL-T8M003 exists with different data. Nothing was changed.';
        END IF;
    ELSE
        -- Mỗi khối EXCEPTION là một savepoint; chỉ thử lại 40P01 tối đa ba lần.
        FOR attempt IN 1..3 LOOP
            BEGIN
                INSERT INTO availability.reservation (
                    code, vehicle_id, period, kind, status, booking_code,
                    reason, hold_expires_at, created_at, status_changed_at
                ) VALUES (
                    'KL-T8M003', target_vehicle_id, fixture_period, 'RENTAL', 'CONFIRMED',
                    'TASK06-MANUAL-ff9d534b-HOURLY', 'Task 6 manual hourly fixture ff9d534b',
                    NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                );
                EXIT;
            EXCEPTION WHEN deadlock_detected THEN
                IF attempt = 3 THEN
                    RAISE;
                END IF;
            END;
        END LOOP;
    END IF;
END;
$fixture$;

SELECT
    r.code AS reservation_code,
    v.code AS vehicle_code,
    r.kind,
    r.status,
    lower(r.period) AT TIME ZONE 'Asia/Ho_Chi_Minh' AS starts_at_vietnam,
    upper(r.period) AT TIME ZONE 'Asia/Ho_Chi_Minh' AS ends_at_vietnam,
    lower_inc(r.period) AS start_inclusive,
    upper_inc(r.period) AS end_inclusive
FROM availability.reservation AS r
JOIN vehicle.vehicle AS v ON v.id = r.vehicle_id
WHERE r.code = 'KL-T8M003'
  AND v.code = 'XE-QWFS1I'
  AND v.model = 'T06-Search-ff9d534b-d760-4e90-b87d-2d0ffac26799'
  AND r.booking_code = 'TASK06-MANUAL-ff9d534b-HOURLY';

COMMIT;
