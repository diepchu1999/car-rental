-- Chỉ chạy thủ công trên database local car_rental, bằng SQL Console IntelliJ.
-- Dọn đúng ba khóa mẫu Task 6 sau nghiệm thu BR-015/104/109/116.
-- Không xóa xe, chi nhánh hoặc khóa lịch khác; không dùng trong production.
-- Kiểm toàn bộ dấu nhận diện trước khi xóa; khác dữ liệu mẫu thì hủy cả transaction.
-- Có thể tạo lại fixture bằng ba file manual_search_*_fixture.sql, nhưng ID/thời điểm tạo sẽ mới.
-- Chạy toàn bộ file khi console không có transaction dở. Nếu lỗi: ROLLBACK và báo lại.
BEGIN;

DO $cleanup$
DECLARE
    expected RECORD;
    actual availability.reservation%ROWTYPE;
    deleted_count INTEGER := 0;
BEGIN
    IF current_database() <> 'car_rental' THEN
        RAISE EXCEPTION 'This cleanup is intended only for the local car_rental database.';
    END IF;

    FOR expected IN
        SELECT * FROM (VALUES
            (
                'KL-T8M001', 'XE-GPQFVV', 'CN-6W0VVV', 'RENTAL', 'CONFIRMED',
                'TASK06-MANUAL-ff9d534b-RENTAL', 'Task 6 manual search fixture',
                TIMESTAMPTZ '2026-10-22 06:00:00+07', TIMESTAMPTZ '2026-10-22 12:00:00+07'
            ),
            (
                'KL-T8M002', 'XE-YYZEY6', 'CN-6W0VVV', 'COMPLIANCE_HOLD', 'BLOCKED',
                NULL, 'Task 6 manual compliance fixture ff9d534b',
                TIMESTAMPTZ '2026-10-22 00:00:00+07', NULL::TIMESTAMPTZ
            ),
            (
                'KL-T8M003', 'XE-QWFS1I', 'CN-QNKBRE', 'RENTAL', 'CONFIRMED',
                'TASK06-MANUAL-ff9d534b-HOURLY', 'Task 6 manual hourly fixture ff9d534b',
                TIMESTAMPTZ '2026-10-22 06:00:00+07', TIMESTAMPTZ '2026-10-22 11:00:00+07'
            )
        ) AS fixtures (
            reservation_code, vehicle_code, branch_code, kind, status,
            booking_code, reason, start_inclusive, end_exclusive
        )
        ORDER BY reservation_code
    LOOP
        -- Giữ khóa bản ghi đã đọc để nó không bị thay đổi giữa kiểm và xóa.
        SELECT r.* INTO actual
        FROM availability.reservation AS r
        WHERE r.code = expected.reservation_code
        FOR UPDATE;

        -- Đã dọn ở lượt trước thì bỏ qua, không yêu cầu chèn lại để xóa.
        IF NOT FOUND THEN
            CONTINUE;
        END IF;

        IF actual.kind IS DISTINCT FROM expected.kind
            OR actual.status IS DISTINCT FROM expected.status
            OR actual.booking_code IS DISTINCT FROM expected.booking_code
            OR actual.reason IS DISTINCT FROM expected.reason
            OR actual.hold_expires_at IS NOT NULL
            OR actual.period IS DISTINCT FROM tstzrange(
                expected.start_inclusive, expected.end_exclusive, '[)'
            )
            OR NOT EXISTS (
                SELECT 1
                FROM vehicle.vehicle AS v
                JOIN branch.branch AS b ON b.id = v.branch_id
                WHERE v.id = actual.vehicle_id
                  AND v.code = expected.vehicle_code
                  AND v.model = 'T06-Search-ff9d534b-d760-4e90-b87d-2d0ffac26799'
                  AND b.code = expected.branch_code
            ) THEN
            RAISE EXCEPTION 'Fixture % differs from expected data. Cleanup was aborted.',
                expected.reservation_code;
        END IF;

        DELETE FROM availability.reservation WHERE id = actual.id;
        deleted_count := deleted_count + 1;
    END LOOP;

    RAISE NOTICE 'Deleted % Task 6 manual reservation fixtures.', deleted_count;
END;
$cleanup$;

COMMIT;

-- Sau COMMIT phải bằng 0. Lượt chạy lại cũng phải bằng 0.
SELECT COUNT(*) AS remaining_manual_reservations
FROM availability.reservation
WHERE code IN ('KL-T8M001', 'KL-T8M002', 'KL-T8M003');

-- Ba xe và hai chi nhánh liên quan vẫn còn, không bị thao tác dọn fixture xóa.
SELECT v.code AS vehicle_code, b.code AS branch_code
FROM vehicle.vehicle AS v
JOIN branch.branch AS b ON b.id = v.branch_id
WHERE v.model = 'T06-Search-ff9d534b-d760-4e90-b87d-2d0ffac26799'
  AND v.code IN ('XE-GPQFVV', 'XE-YYZEY6', 'XE-QWFS1I')
ORDER BY v.code;
