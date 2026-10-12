-- Chỉ test: không bỏ qua trùng mã, cho phép truyền giá trị SQL đặc biệt qua CAST để kiểm CHECK.
INSERT INTO pricing.quote (
    code, vehicle_code, start_at, end_at, rental_type, drive_mode, pickup_method,
    daily_rate, weekend_days, weekend_uplift, holiday_uplift,
    insurance_calculation, insurance_value, insurance_billing_days, quote_validity_seconds,
    rental_amount, insurance_fee, total_amount, created_at, expires_at, expires_at_submicro_nanos
) VALUES (
    :code, :vehicleCode, CAST(:startAt AS TIMESTAMPTZ), CAST(:endAt AS TIMESTAMPTZ), :rentalType, :driveMode, :pickupMethod,
    :dailyRate, CAST(:weekendDays AS TEXT[]), CAST(:weekendUplift AS NUMERIC), CAST(:holidayUplift AS NUMERIC),
    :insuranceCalculation, CAST(:insuranceValue AS NUMERIC), :insuranceBillingDays, CAST(:quoteValiditySeconds AS NUMERIC),
    :rentalAmount, :insuranceFee, :totalAmount, CAST(:createdAt AS TIMESTAMPTZ), CAST(:expiresAt AS TIMESTAMPTZ), :expiresAtSubmicroNanos
) RETURNING id;
