-- Chỉ dùng trong test để kiểm tra ràng buộc của bảng xe.
-- Truyền dữ liệu trực tiếp xuống PostgreSQL, không qua validation của domain.
-- Không dùng ON CONFLICT vì test cần nhận lỗi khi trùng mã hoặc biển số.
INSERT INTO vehicle.vehicle (
    code,
    plate_number,
    ownership_type,
    fuel_type,
    branch_id,
    status,
    inspection_expires_on,
    liability_insurance_expires_on
)
VALUES (
           :code,
           :plateNumber,
           :ownershipType,
           :fuelType,
           :branchId,
           :status,
           :inspectionExpiresOn,
           :liabilityInsuranceExpiresOn
       );