-- BR-018: thuộc tính tìm kiếm bắt buộc từ lúc tạo xe.
-- Không backfill giá trị phỏng đoán; kiểm CSDL local trống trước khi nâng cấp.
ALTER TABLE vehicle.vehicle
    ADD COLUMN seats INTEGER NOT NULL,
    ADD COLUMN transmission TEXT NOT NULL,
    ADD COLUMN make TEXT NOT NULL,
    ADD COLUMN model TEXT NOT NULL,
    ADD CONSTRAINT chk_vehicle_seats CHECK (seats IN (4, 5, 7, 16)),
    ADD CONSTRAINT chk_vehicle_transmission CHECK (transmission IN ('MANUAL', 'AUTOMATIC')),
    ADD CONSTRAINT chk_vehicle_make_not_blank CHECK (make ~ '[^[:space:]]'),
    ADD CONSTRAINT chk_vehicle_model_not_blank CHECK (model ~ '[^[:space:]]');
