-- Chỉ chạy thủ công trên database local car_rental, bằng SQL Console IntelliJ.
-- Fixture Task 6 đã được Tech Owner xác nhận; không phải migration hoặc SQL production.
-- BR-104/109: chuyến mẫu 06:00–10:00, đệm hai giờ, khoảng lưu là [06:00,12:00).
-- CONFIRMED giúp job dọn HELD không thay đổi kết quả giữa các bước kiểm thủ công.
-- Chạy toàn bộ file trên console không có transaction dở. Nếu lỗi: ROLLBACK và báo lại.
-- Phải COMMIT để kết nối riêng của backend nhìn thấy fixture khi gọi Postman.
BEGIN;

DO $fixture$
DECLARE
    target_vehicle_id BIGINT;
    fixture_period TSTZRANGE := tstzrange(
        TIMESTAMPTZ '2026-10-22 06:00:00+07',
        TIMESTAMPTZ '2026-10-22 12:00:00+07',
        '[)'
    );
BEGIN
    IF current_database() <> 'car_rental' THEN
        RAISE EXCEPTION 'This fixture is intended only for the local car_rental database.';
    END IF;

    SELECT v.id INTO STRICT target_vehicle_id
    FROM vehicle.vehicle AS v
    JOIN branch.branch AS b ON b.id = v.branch_id
    WHERE v.code = 'XE-GPQFVV'
      AND v.model = 'T06-Search-ff9d534b-d760-4e90-b87d-2d0ffac26799'
      AND v.status = 'ACTIVE'
      AND b.code = 'CN-6W0VVV';

    -- Chạy lại chỉ được bỏ qua khi đúng bản ghi fixture nguyên trạng đã tồn tại.
    -- Không sửa hoặc ghi đè dữ liệu có sẵn khi mã bị trùng.
    IF EXISTS (
        SELECT 1 FROM availability.reservation WHERE code = 'KL-T8M001'
    ) THEN
        IF NOT EXISTS (
            SELECT 1
            FROM availability.reservation
            WHERE code = 'KL-T8M001'
              AND vehicle_id = target_vehicle_id
              AND period = fixture_period
              AND kind = 'RENTAL'
              AND status = 'CONFIRMED'
              AND booking_code = 'TASK06-MANUAL-ff9d534b-RENTAL'
              AND reason = 'Task 6 manual search fixture'
              AND hold_expires_at IS NULL
        ) THEN
            RAISE EXCEPTION 'Fixture code KL-T8M001 exists with different data. Nothing was changed.';
        END IF;
    ELSE
        -- Mỗi khối EXCEPTION là một savepoint; chỉ thử lại 40P01 tối đa ba lần.
        FOR attempt IN 1..3 LOOP
            BEGIN
                INSERT INTO availability.reservation (
                    code, vehicle_id, period, kind, status, booking_code,
                    reason, hold_expires_at, created_at, status_changed_at
                ) VALUES (
                    'KL-T8M001', target_vehicle_id, fixture_period, 'RENTAL', 'CONFIRMED',
                    'TASK06-MANUAL-ff9d534b-RENTAL', 'Task 6 manual search fixture',
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
WHERE r.code = 'KL-T8M001'
  AND v.code = 'XE-GPQFVV'
  AND v.model = 'T06-Search-ff9d534b-d760-4e90-b87d-2d0ffac26799'
  AND r.booking_code = 'TASK06-MANUAL-ff9d534b-RENTAL';

COMMIT;
