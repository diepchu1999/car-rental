-- Ghi trực tiếp dữ liệu test, không qua domain hoặc validation của application.
-- Cho phép chọn hình dạng khoảng để cố ý thử vi phạm CHECK.
INSERT INTO availability.reservation (
    code, vehicle_id, period, kind, status, booking_code, reason,
    hold_expires_at, created_at, status_changed_at
)
VALUES (
    :code, :vehicleId,
    tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz), :bounds),
    :kind, :status, :bookingCode, :reason,
    :holdExpiresAt, :createdAt, :statusChangedAt
)
RETURNING id;
