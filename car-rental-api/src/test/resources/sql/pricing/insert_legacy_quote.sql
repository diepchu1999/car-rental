-- Chỉ dùng trước V015 trong container test; cố ý không có chủ, không đoán hoặc gán chủ lịch sử.
INSERT INTO pricing.quote (
    code, vehicle_code, start_at, end_at, rental_type, drive_mode, pickup_method,
    daily_rate, weekend_days, weekend_uplift, holiday_uplift,
    insurance_calculation, insurance_value, insurance_billing_days, quote_validity_seconds,
    rental_amount, insurance_fee, total_amount, created_at, expires_at, expires_at_submicro_nanos
) VALUES (
    :code, 'XE-LEGACY', '2026-11-13T03:00:00Z', '2026-11-14T03:00:00Z', 'DAILY', 'SELF_DRIVE', 'BRANCH',
    1000000, ARRAY['SATURDAY', 'SUNDAY'], 0.2, 0.5, 'PER_BILLING_DAY', 100000, 1, 900,
    1000000, 100000, 1100000, '2026-11-01T03:00:00Z', '2026-11-01T03:15:00Z', 0
) RETURNING id;
