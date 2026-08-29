-- Schema riêng của module vehicle theo ADR-0008.
CREATE SCHEMA vehicle;

-- Dữ liệu xe trong phạm vi Task 4.
-- Loại sở hữu: BR-001.
-- Chi nhánh của xe công ty: BR-003.
-- Hạn giấy tờ: BR-005.
-- Trạng thái duyệt: BR-010 và status-flow mục 4.
-- Loại nhiên liệu: BR-410.
CREATE TABLE vehicle.vehicle (
                                 id BIGINT GENERATED ALWAYS AS IDENTITY,
                                 code TEXT NOT NULL,
                                 plate_number TEXT NOT NULL,
                                 ownership_type TEXT NOT NULL,
                                 fuel_type TEXT NOT NULL,
                                 branch_id BIGINT NULL,
                                 status TEXT NOT NULL,
                                 inspection_expires_on DATE NULL,
                                 liability_insurance_expires_on DATE NULL,

                                 CONSTRAINT pk_vehicle
                                     PRIMARY KEY (id),

                                 CONSTRAINT uq_vehicle_code
                                     UNIQUE (code),

                                 CONSTRAINT uq_vehicle_plate_number
                                     UNIQUE (plate_number),

                                 CONSTRAINT chk_vehicle_code_format
                                     CHECK (code COLLATE "C" ~ '^XE-[A-Z0-9]{6}$'),

                                 CONSTRAINT chk_vehicle_ownership_type
                                     CHECK (ownership_type IN ('COMPANY', 'PARTNER')),

                                 CONSTRAINT chk_vehicle_fuel_type
                                     CHECK (fuel_type IN ('PETROL', 'DIESEL', 'ELECTRIC', 'HYBRID')),

                                 CONSTRAINT chk_vehicle_status
                                     CHECK (
                                         status IN (
                                                    'DRAFT',
                                                    'PENDING_APPROVAL',
                                                    'ACTIVE',
                                                    'INACTIVE',
                                                    'REJECTED',
                                                    'RETIRED'
                                             )
                                         ),

                                 CONSTRAINT chk_vehicle_branch_id_positive
                                     CHECK (branch_id IS NULL OR branch_id > 0),

                                 CONSTRAINT chk_vehicle_company_has_branch
                                     CHECK (ownership_type <> 'COMPANY' OR branch_id IS NOT NULL)
);

-- BR-001: loại sở hữu bất biến sau khi tạo.
-- CHECK thông thường không so sánh được bản ghi cũ với bản ghi mới,
-- nên dùng trigger để kiểm OLD và NEW khi cập nhật.
CREATE FUNCTION vehicle.prevent_ownership_type_change()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.ownership_type IS DISTINCT FROM OLD.ownership_type THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Vehicle ownership type cannot be changed after creation.',
            SCHEMA = TG_TABLE_SCHEMA,
            TABLE = TG_TABLE_NAME,
            COLUMN = 'ownership_type',
            CONSTRAINT = 'chk_vehicle_ownership_type_immutable';
    END IF;

    RETURN NEW;
END;
$function$;

-- Kiểm từng bản ghi trước khi cập nhật.
-- Cập nhật trạng thái hoặc gán lại cùng loại sở hữu vẫn được phép.
CREATE TRIGGER trg_vehicle_ownership_type_immutable
    BEFORE UPDATE ON vehicle.vehicle
    FOR EACH ROW
EXECUTE FUNCTION vehicle.prevent_ownership_type_change();