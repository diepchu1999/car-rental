-- Chỉ bỏ qua trùng mã; lỗi constraint khác phải làm rollback giao dịch.
INSERT INTO pricing.quote (
    code, vehicle_code, start_at, end_at, rental_type, drive_mode, pickup_method,
    daily_rate, weekend_days, weekend_uplift, holiday_uplift,
    insurance_calculation, insurance_value, insurance_billing_days, quote_validity_seconds,
    rental_amount, insurance_fee, total_amount, created_at, expires_at, expires_at_submicro_nanos
) VALUES (
    :code, :vehicleCode, :startAt, :endAt, :rentalType, :driveMode, :pickupMethod,
    :dailyRate, CAST(:weekendDays AS TEXT[]), :weekendUplift, :holidayUplift,
    :insuranceCalculation, :insuranceValue, :insuranceBillingDays, :quoteValiditySeconds,
    :rentalAmount, :insuranceFee, :totalAmount, :createdAt, :expiresAt, :expiresAtSubmicroNanos
)
ON CONFLICT ON CONSTRAINT uq_quote_code DO NOTHING
RETURNING id;
