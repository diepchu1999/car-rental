-- Task 6 / Phần 4.2: chạy cả file trong IntelliJ SQL Console của car_rental.
-- BR-010/018/126: kiểm SQL lọc danh mục, chưa kiểm lịch trống hoặc policy thế chấp Java.
-- Dùng kết nối không có giao dịch công việc khác đang mở.
-- Mọi dữ liệu mẫu đều nằm trong BEGIN/ROLLBACK; không COMMIT.
-- Nếu có lỗi, chạy riêng ROLLBACK; trước khi sửa và chạy lại.
-- Nếu trùng mã fixture, dừng để kiểm tra, không xóa/ghi đè dữ liệu cũ.
BEGIN;

INSERT INTO branch.branch (code, location, name, address)
VALUES
    ('CN-T6S001', public.ST_SetSRID(public.ST_MakePoint(106.66, 10.76), 4326)::public.geography,
     'Search Test Branch One', 'Search Test Address One'),
    ('CN-T6S002', public.ST_SetSRID(public.ST_MakePoint(107.66, 11.76), 4326)::public.geography,
     'Search Test Branch Two', 'Search Test Address Two');

INSERT INTO vehicle.vehicle (
    code, plate_number, ownership_type, fuel_type, branch_id, status,
    inspection_expires_on, liability_insurance_expires_on, seats, transmission, make, model
)
SELECT f.code, f.plate_number, 'COMPANY', f.fuel_type, b.id, f.status,
       CURRENT_DATE + 365, CURRENT_DATE + 365, f.seats, f.transmission, f.make, f.model
FROM (VALUES
    ('XE-T6S001', 'T06-P42-001', 'CN-T6S001', 'ACTIVE', 'PETROL', 5, 'AUTOMATIC', ' Toyota ', ' Vios '),
    ('XE-T6S002', 'T06-P42-002', 'CN-T6S001', 'DRAFT', 'PETROL', 5, 'AUTOMATIC', 'Toyota', 'Vios'),
    ('XE-T6S003', 'T06-P42-003', 'CN-T6S002', 'ACTIVE', 'PETROL', 5, 'AUTOMATIC', 'Toyota', 'Vios'),
    ('XE-T6S004', 'T06-P42-004', 'CN-T6S001', 'ACTIVE', 'DIESEL', 7, 'MANUAL', 'Ford', 'Everest')
) AS f(code, plate_number, branch_code, status, fuel_type, seats, transmission, make, model)
JOIN branch.branch b ON b.code = f.branch_code;

-- Kết quả 1: đúng hai dòng XE-T6S001 và XE-T6S004.
-- Xe DRAFT và xe ở chi nhánh thứ hai không được xuất hiện.
SELECT code, seats, transmission, fuel_type, make, model
FROM vehicle.vehicle
WHERE status = 'ACTIVE'
  AND branch_id IN (SELECT id FROM branch.branch WHERE code = 'CN-T6S001')
ORDER BY id;

-- Kết quả 2: chỉ XE-T6S001; hãng/dòng khớp toàn bộ sau khi bỏ trắng và bỏ phân biệt hoa thường.
SELECT code, seats, transmission, fuel_type, make, model
FROM vehicle.vehicle
WHERE status = 'ACTIVE'
  AND branch_id IN (SELECT id FROM branch.branch WHERE code = 'CN-T6S001')
  AND seats = 5
  AND transmission = 'AUTOMATIC'
  AND fuel_type = 'PETROL'
  AND lower(regexp_replace(make, '^[[:space:]]+|[[:space:]]+$', '', 'g')) = lower('tOyOtA')
  AND lower(regexp_replace(model, '^[[:space:]]+|[[:space:]]+$', '', 'g')) = lower('vIoS')
ORDER BY id;

-- Kết quả 3: không có dòng; phần tên và wildcard không được coi là khớp.
SELECT code
FROM vehicle.vehicle
WHERE status = 'ACTIVE'
  AND branch_id IN (SELECT id FROM branch.branch WHERE code = 'CN-T6S001')
  AND lower(regexp_replace(make, '^[[:space:]]+|[[:space:]]+$', '', 'g'))
      IN (lower('Toy'), lower('%'));

ROLLBACK;

-- Kết quả 4: cả hai số đếm đều bằng 0, xác nhận không lưu lại fixture.
SELECT
    (SELECT count(*) FROM branch.branch WHERE code IN ('CN-T6S001', 'CN-T6S002')) AS remaining_branches,
    (SELECT count(*) FROM vehicle.vehicle
     WHERE code IN ('XE-T6S001', 'XE-T6S002', 'XE-T6S003', 'XE-T6S004')) AS remaining_vehicles;
