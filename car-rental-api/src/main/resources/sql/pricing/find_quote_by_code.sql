-- Không lọc expires_at: báo giá hết hạn vẫn được đọc theo BR-122.
SELECT id, code, vehicle_code, start_at, end_at, rental_type, drive_mode, pickup_method,
       daily_rate, weekend_days, weekend_uplift, holiday_uplift,
       insurance_calculation, insurance_value, insurance_billing_days, quote_validity_seconds,
       rental_amount, insurance_fee, total_amount, created_at, expires_at, expires_at_submicro_nanos
FROM pricing.quote WHERE code = :code;
