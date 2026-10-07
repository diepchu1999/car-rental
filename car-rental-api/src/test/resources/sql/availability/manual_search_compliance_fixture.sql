-- Chỉ chạy thủ công trên database local car_rental, bằng SQL Console IntelliJ.
-- BR-015: mô phỏng khóa giấy tờ từ 09/10/2026 00:00 giờ Việt Nam, không có cận trên.
-- Chỉ kiểm search tôn trọng khóa đã có; không kiểm job tự tạo khóa hoặc sửa hạn giấy tờ xe.
-- Dữ liệu mẫu cố định của lượt nghiệm thu Task 6, không phải migration hoặc SQL production.
-- Chạy toàn bộ file khi console không có transaction dở. Nếu lỗi: ROLLBACK và báo lại.
-- Phải COMMIT để backend nhìn thấy fixture qua kết nối riêng.
BEGIN;

DO $fixture$
DECLARE
    target_vehicle_id BIGINT;
    fixture_period TSTZRANGE := tstzrange(
        TIMESTAMPTZ '2026-10-09 00:00:00+07',
        NULL,
        '[)'
    );
BEGIN
    SELECT v.id INTO STRICT target_vehicle_id
    FROM vehicle.vehicle AS v
    JOIN branch.branch AS b ON b.id = v.branch_id
    WHERE v.code = 'XE-09Z3NS'
      AND v.model = 'T06-Search-7fa9992b-83b8-4993-8928-ded8c0f0d042'
      AND v.status = 'ACTIVE'
      AND b.code = 'CN-JMRGMT';

    -- Chạy lại chỉ bỏ qua bản ghi đúng nguyên trạng, không ghi đè khi trùng mã.
    IF EXISTS (
        SELECT 1 FROM availability.reservation WHERE code = 'KL-T6M002'
    ) THEN
        IF NOT EXISTS (
            SELECT 1
            FROM availability.reservation
            WHERE code = 'KL-T6M002'
              AND vehicle_id = target_vehicle_id
              AND period = fixture_period
              AND kind = 'COMPLIANCE_HOLD'
              AND status = 'BLOCKED'
              AND booking_code IS NULL
              AND reason = 'Task 6 manual compliance fixture 7fa9992b'
              AND hold_expires_at IS NULL
        ) THEN
            RAISE EXCEPTION 'Fixture code KL-T6M002 exists with different data. Nothing was changed.';
        END IF;
    ELSE
        INSERT INTO availability.reservation (
            code, vehicle_id, period, kind, status, booking_code,
            reason, hold_expires_at, created_at, status_changed_at
        ) VALUES (
            'KL-T6M002', target_vehicle_id, fixture_period, 'COMPLIANCE_HOLD', 'BLOCKED',
            NULL, 'Task 6 manual compliance fixture 7fa9992b',
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
    upper_inf(r.period) AS no_end,
    lower_inc(r.period) AS start_inclusive
FROM availability.reservation AS r
JOIN vehicle.vehicle AS v ON v.id = r.vehicle_id
WHERE r.code = 'KL-T6M002'
  AND v.code = 'XE-09Z3NS'
  AND v.model = 'T06-Search-7fa9992b-83b8-4993-8928-ded8c0f0d042'
  AND r.reason = 'Task 6 manual compliance fixture 7fa9992b';

COMMIT;
