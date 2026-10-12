-- BR-234: hồ sơ chưa duyệt có thể chưa nhập giá; không backfill giá phỏng đoán.
ALTER TABLE vehicle.vehicle
    ADD COLUMN daily_rate BIGINT NULL,
    ADD CONSTRAINT chk_vehicle_daily_rate
        CHECK (daily_rate IS NULL OR (daily_rate > 0 AND daily_rate % 1000 = 0)),
    ADD CONSTRAINT chk_vehicle_approved_has_daily_rate
        CHECK (status NOT IN ('ACTIVE', 'INACTIVE', 'RETIRED') OR daily_rate IS NOT NULL);
