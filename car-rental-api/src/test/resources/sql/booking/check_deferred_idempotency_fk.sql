-- Ép kiểm FK đang hoãn để test bắt đúng lỗi trước khi transaction được rollback.
SET CONSTRAINTS booking.fk_booking_idempotency_booking IMMEDIATE;
