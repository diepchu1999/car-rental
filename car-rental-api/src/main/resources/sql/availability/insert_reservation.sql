-- Mọi nguyên nhân khóa lịch dùng chung bảng theo BR-104, ADR-0005.
-- Application đã cộng đệm vào khoảng thuê theo BR-109, BR-116.
-- Các mốc thời gian được cung cấp từ Clock chung.
-- Hạn giữ chỗ đã được tính theo BR-103, BR-225; không tính lại ở SQL.
--
-- PostgreSQL dựng khoảng [): cận dưới đóng, cận trên mở.
-- endExclusive bằng NULL biểu diễn không chặn trên.
-- Domain và CHECK chỉ cho COMPLIANCE_HOLD dùng khoảng không chặn trên.
--
-- Chỉ bỏ qua xung đột mã reservation.
-- Không bỏ qua lỗi chống trùng lịch hoặc các lỗi toàn vẹn khác.
-- Không cập nhật bản ghi cũ khi trùng mã.
INSERT INTO availability.reservation (
    code,
    vehicle_id,
    period,
    kind,
    status,
    booking_code,
    reason,
    hold_expires_at,
    created_at,
    status_changed_at
)
VALUES (
           :code,
           :vehicleId,
           tstzrange(
                   CAST(:startInclusive AS timestamptz),
                   CAST(:endExclusive AS timestamptz),
                   '[)'
           ),
           :kind,
           :status,
           :bookingCode,
           :reason,
           :holdExpiresAt,
           :createdAt,
           :statusChangedAt
       )
ON CONFLICT ON CONSTRAINT uq_reservation_code DO NOTHING
RETURNING id;