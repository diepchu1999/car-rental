-- Dữ liệu thử trực tiếp để chứng minh constraint; không đi qua validation Java.
INSERT INTO booking.price_line (
    booking_id,
    line_number,
    type,
    day_type,
    start_at,
    hours,
    daily_rate,
    uplift,
    amount
) VALUES (
    CAST(:booking_id AS bigint),
    CAST(:line_number AS integer),
    CAST(:type AS text),
    CAST(:day_type AS text),
    CAST(:start_at AS timestamptz),
    CAST(:hours AS integer),
    CAST(:daily_rate AS bigint),
    CAST(:uplift AS numeric),
    CAST(:amount AS bigint)
);
