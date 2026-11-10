-- Không cho sửa riêng hạn để lệch khỏi bản sao đã chốt.
UPDATE booking.booking SET hold_expires_at = hold_expires_at + INTERVAL '1 minute' WHERE id = :id;
