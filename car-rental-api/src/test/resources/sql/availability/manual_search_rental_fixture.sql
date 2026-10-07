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
        TIMESTAMPTZ '2026-10-09 06:00:00+07',
        TIMESTAMPTZ '2026-10-09 12:00:00+07',
        '[)'
    );
BEGIN
    SELECT v.id INTO STRICT target_vehicle_id
    FROM vehicle.vehicle AS v
    JOIN branch.branch AS b ON b.id = v.branch_id
    WHERE v.code = 'XE-B76A44'
      AND v.model = 'T06-Search-7fa9992b-83b8-4993-8928-ded8c0f0d042'
      AND v.status = 'ACTIVE'
      AND b.code = 'CN-JMRGMT';

    -- Chạy lại chỉ được bỏ qua khi đúng bản ghi fixture nguyên trạng đã tồn tại.
    -- Không sửa hoặc ghi đè dữ liệu có sẵn khi mã bị trùng.
    IF EXISTS (
        SELECT 1 FROM availability.reservation WHERE code = 'KL-T6M001'
    ) THEN
        IF NOT EXISTS (
            SELECT 1
            FROM availability.reservation
            WHERE code = 'KL-T6M001'
              AND vehicle_id = target_vehicle_id
              AND period = fixture_period
              AND kind = 'RENTAL'
              AND status = 'CONFIRMED'
              AND booking_code = 'TASK06-MANUAL-7fa9992b-RENTAL'
              AND reason = 'Task 6 manual search fixture'
              AND hold_expires_at IS NULL
        ) THEN
            RAISE EXCEPTION 'Fixture code KL-T6M001 exists with different data. Nothing was changed.';
        END IF;
    ELSE
        INSERT INTO availability.reservation (
            code, vehicle_id, period, kind, status, booking_code,
            reason, hold_expires_at, created_at, status_changed_at
        ) VALUES (
            'KL-T6M001', target_vehicle_id, fixture_period, 'RENTAL', 'CONFIRMED',
            'TASK06-MANUAL-7fa9992b-RENTAL', 'Task 6 manual search fixture',
            NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
        );
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
WHERE r.code = 'KL-T6M001'
  AND v.code = 'XE-B76A44'
  AND v.model = 'T06-Search-7fa9992b-83b8-4993-8928-ded8c0f0d042'
  AND r.booking_code = 'TASK06-MANUAL-7fa9992b-RENTAL';

COMMIT;
