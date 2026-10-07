-- Task 6, Phần 4.1 — BR-003, BR-808, ADR-0007.
-- Chạy toàn bộ file trong IntelliJ SQL Console, database car_rental đã có V007.
-- Chỉ tạo fixture trong transaction; ROLLBACK cuối file loại bỏ chúng.
-- Nếu có lỗi giữa chừng, chạy riêng ROLLBACK; không COMMIT.
-- Trùng mã fixture thì dừng, không dùng ON CONFLICT để sửa dữ liệu sẵn có.
BEGIN;

INSERT INTO branch.branch (code, name, address, location)
VALUES
    ('CN-T6G001', 'Origin Fixture', 'Street 1',
     CAST(public.ST_SetSRID(public.ST_MakePoint(-120, -80), 4326) AS public.geography)),
    ('CN-T6G002', 'Near Fixture', 'Street 2',
     CAST(public.ST_SetSRID(public.ST_MakePoint(-120, -79.999), 4326) AS public.geography)),
    ('CN-T6G003', 'Far Fixture', 'Street 3',
     CAST(public.ST_SetSRID(public.ST_MakePoint(-120, -79), 4326) AS public.geography));

-- 200 mét: đúng CN-T6G001 (0 m) và CN-T6G002 (khoảng 112 m).
-- Bộ lọc mã chỉ để tách fixture khỏi dữ liệu local khác.
WITH origin AS (
    SELECT CAST(public.ST_SetSRID(public.ST_MakePoint(-120, -80), 4326)
                AS public.geography) AS point
)
SELECT b.code, b.name, b.address,
       public.ST_Distance(b.location, origin.point) AS distance_meters
FROM branch.branch AS b
CROSS JOIN origin
WHERE public.ST_DWithin(b.location, origin.point, 200)
  AND b.code IN ('CN-T6G001', 'CN-T6G002', 'CN-T6G003')
ORDER BY distance_meters, b.id;

-- Thu hẹp còn 50 mét: chỉ CN-T6G001; hai chi nhánh còn lại phải bị loại.
WITH origin AS (
    SELECT CAST(public.ST_SetSRID(public.ST_MakePoint(-120, -80), 4326)
                AS public.geography) AS point
)
SELECT b.code,
       public.ST_Distance(b.location, origin.point) AS distance_meters
FROM branch.branch AS b
CROSS JOIN origin
WHERE public.ST_DWithin(b.location, origin.point, 50)
  AND b.code IN ('CN-T6G001', 'CN-T6G002', 'CN-T6G003')
ORDER BY distance_meters, b.id;

ROLLBACK;

-- Sau rollback phải bằng 0; không để lại dữ liệu mẫu cần dọn ở bước sau.
SELECT COUNT(*) AS remaining_manual_fixtures
FROM branch.branch
WHERE code IN ('CN-T6G001', 'CN-T6G002', 'CN-T6G003');
