-- Chỉ dựng fixture CANCELLED để thử key hết hạn; không phải use case huỷ đơn.
UPDATE booking.booking SET status = 'CANCELLED', status_changed_at = :changedAt WHERE code = :code;
